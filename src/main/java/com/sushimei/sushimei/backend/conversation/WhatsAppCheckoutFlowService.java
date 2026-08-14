package com.sushimei.sushimei.backend.conversation;

import com.sushimei.sushimei.backend.checkout.ActiveCartNotFoundException;
import com.sushimei.sushimei.backend.checkout.CartLineSnapshot;
import com.sushimei.sushimei.backend.checkout.CartSnapshot;
import com.sushimei.sushimei.backend.checkout.CartSnapshotService;
import com.sushimei.sushimei.backend.checkout.CheckoutCompletionCommand;
import com.sushimei.sushimei.backend.checkout.CheckoutCompletionResult;
import com.sushimei.sushimei.backend.checkout.EmptyCartException;
import com.sushimei.sushimei.backend.checkout.MultipleActiveCartsException;
import com.sushimei.sushimei.backend.checkout.OrderService;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.service.CartService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Deterministic WhatsApp checkout adapter. AI remains responsible for menu conversation and
 * cart mutations; this service alone advances persisted checkout state and creates orders.
 */
@Service
public class WhatsAppCheckoutFlowService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppCheckoutFlowService.class);

    private final ConversationSessionService sessionService;
    private final CheckoutIntentRouter intentRouter;
    private final CartSnapshotService cartSnapshotService;
    private final OrderService orderService;
    private final CartService cartService;

    public WhatsAppCheckoutFlowService(ConversationSessionService sessionService,
                                       CheckoutIntentRouter intentRouter,
                                       CartSnapshotService cartSnapshotService,
                                       OrderService orderService,
                                       CartService cartService) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
        this.intentRouter = Objects.requireNonNull(intentRouter, "intentRouter must not be null");
        this.cartSnapshotService = Objects.requireNonNull(cartSnapshotService,
                "cartSnapshotService must not be null");
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
        this.cartService = Objects.requireNonNull(cartService, "cartService must not be null");
    }

    public Optional<String> handleText(String phoneNumber, String message) {
        ConversationSession session = sessionService.getOrCreateSession(phoneNumber);
        if (WhatsAppCheckoutMessageParser.isClearCart(message)) {
            boolean cleared = cartService.clearCart(phoneNumber);
            sessionService.resetSession(phoneNumber);
            LOGGER.info("whatsapp_cart_reset requestId={} outcome={}", MDC.get("requestId"),
                    cleared ? "CLEARED" : "ALREADY_EMPTY");
            return Optional.of(cleared
                    ? "Listo, vacié tu carrito. Dime nuevamente qué productos deseas agregar."
                    : "Tu carrito ya estaba vacío. Dime qué productos deseas agregar.");
        }
        if (session.getState() == ConversationState.CANCELLED) {
            sessionService.resetSession(phoneNumber);
            return Optional.empty();
        }
        if (session.getState() == ConversationState.ORDER_CONFIRMED) {
            if (WhatsAppCheckoutMessageParser.isAffirmative(message)
                    || WhatsAppCheckoutMessageParser.isFinishOrder(message)) {
                return Optional.of("Tu pedido ya fue confirmado. Si deseas iniciar otro, indícame qué producto quieres agregar.");
            }
            sessionService.resetSession(phoneNumber);
            return Optional.empty();
        }
        if (session.getState() == ConversationState.ORDERING) {
            return WhatsAppCheckoutMessageParser.isFinishOrder(message)
                    ? Optional.of(startReview(phoneNumber))
                    : Optional.empty();
        }
        if (WhatsAppCheckoutMessageParser.isCancel(message)) {
            intentRouter.route(phoneNumber, new CheckoutIntent.CancelCheckout());
            return Optional.of("De acuerdo, detuve la confirmación. Tu carrito se conserva por si deseas retomarlo.");
        }

        return Optional.of(switch (session.getState()) {
            case WAITING_CART_CONFIRMATION -> handleCartConfirmation(phoneNumber, message);
            case WAITING_FULFILLMENT_TYPE -> handleFulfillment(phoneNumber, message);
            case WAITING_DELIVERY_ADDRESS -> handleDeliveryAddress(phoneNumber, message);
            case WAITING_PICKUP_NAME -> handlePickupName(phoneNumber, message);
            case WAITING_PAYMENT_METHOD -> handlePayment(phoneNumber, session, message);
            case WAITING_CASH_DENOMINATION -> handleCashDenomination(phoneNumber, message);
            case WAITING_TRANSFER_RECEIPT ->
                    "Envíame una imagen del comprobante de transferencia para continuar.";
            case READY_TO_CONFIRM -> handleFinalConfirmation(phoneNumber, session, message);
            case ORDERING, ORDER_CONFIRMED, CANCELLED -> throw new IllegalStateException("State handled before switch");
        });
    }

    public Optional<String> handleImage(String phoneNumber, String receiptPath) {
        Optional<ConversationSession> existing = sessionService.findSession(phoneNumber);
        if (existing.isEmpty() || existing.get().getState() != ConversationState.WAITING_TRANSFER_RECEIPT) {
            return Optional.empty();
        }
        if (receiptPath == null || receiptPath.isBlank()) {
            return Optional.of("No pude guardar el comprobante. Intenta enviarlo nuevamente como imagen.");
        }
        try {
            intentRouter.route(phoneNumber, new CheckoutIntent.ProvideTransferReceipt(receiptPath));
            return Optional.of(readySummary(phoneNumber));
        } catch (RuntimeException exception) {
            logFailure("whatsapp_checkout_receipt_failed", exception);
            return Optional.of("No pude registrar el comprobante. Intenta enviarlo nuevamente.");
        }
    }

    private String startReview(String phoneNumber) {
        try {
            CartSnapshot snapshot = cartSnapshotService.readActiveCart(phoneNumber);
            intentRouter.route(phoneNumber, new CheckoutIntent.RequestCheckoutReview());
            return formatCart(snapshot)
                    + "\n\n¿El carrito está correcto? Responde 'sí' para continuar o 'no' para modificarlo.";
        } catch (ActiveCartNotFoundException | EmptyCartException exception) {
            return "Tu carrito está vacío. Agrega al menos un producto antes de finalizar el pedido.";
        } catch (MultipleActiveCartsException exception) {
            logFailure("whatsapp_checkout_cart_conflict", exception);
            return "No pude identificar un único carrito activo. Solicita ayuda al restaurante para continuar.";
        } catch (RuntimeException exception) {
            logFailure("whatsapp_checkout_review_failed", exception);
            return "No pude preparar el resumen del pedido. Intenta nuevamente.";
        }
    }

    private String handleCartConfirmation(String phoneNumber, String message) {
        if (WhatsAppCheckoutMessageParser.isNegative(message)) {
            intentRouter.route(phoneNumber, new CheckoutIntent.ContinueOrdering());
            return "Perfecto, dime qué producto deseas agregar o quitar.";
        }
        if (WhatsAppCheckoutMessageParser.isAffirmative(message)) {
            intentRouter.route(phoneNumber, new CheckoutIntent.ConfirmCart());
            return "¿Tu pedido es para entrega a domicilio o para recoger en sucursal?";
        }
        return "Responde 'sí' si el carrito está correcto o 'no' si deseas modificarlo.";
    }

    private String handleFulfillment(String phoneNumber, String message) {
        FulfillmentType fulfillment = WhatsAppCheckoutMessageParser.fulfillment(message);
        if (fulfillment == null) {
            return "Elige una opción: entrega a domicilio o recoger en sucursal.";
        }
        intentRouter.route(phoneNumber, new CheckoutIntent.SelectFulfillment(fulfillment));
        return fulfillment == FulfillmentType.DELIVERY
                ? "Escribe la dirección completa de entrega."
                : "¿A nombre de quién quedará el pedido para recoger?";
    }

    private String handleDeliveryAddress(String phoneNumber, String message) {
        try {
            intentRouter.route(phoneNumber, new CheckoutIntent.ProvideDeliveryAddress(message));
            return paymentPrompt(false);
        } catch (InvalidConversationTransitionException exception) {
            return "La dirección debe tener al menos 5 caracteres. Escríbela completa, por favor.";
        }
    }

    private String handlePickupName(String phoneNumber, String message) {
        try {
            intentRouter.route(phoneNumber, new CheckoutIntent.ProvidePickupName(message));
            return paymentPrompt(true);
        } catch (InvalidConversationTransitionException exception) {
            return "Escribe un nombre de al menos 2 caracteres para identificar el pedido.";
        }
    }

    private String handlePayment(String phoneNumber, ConversationSession session, String message) {
        PaymentMethod payment = WhatsAppCheckoutMessageParser.payment(message);
        if (payment == null) {
            return paymentPrompt(session.getFulfillmentType() == FulfillmentType.PICKUP);
        }
        if (payment == PaymentMethod.CARD && session.getFulfillmentType() != FulfillmentType.PICKUP) {
            return "El pago con tarjeta solo está disponible al recoger. Elige efectivo o transferencia.";
        }
        intentRouter.route(phoneNumber, new CheckoutIntent.SelectPaymentMethod(payment));
        return switch (payment) {
            case CASH -> "¿Con cuánto efectivo pagarás? Por ejemplo: 200 o 500.";
            case TRANSFER -> "Envía una imagen del comprobante de transferencia para continuar.";
            case CARD -> readySummary(phoneNumber);
        };
    }

    private String handleCashDenomination(String phoneNumber, String message) {
        BigDecimal denomination = WhatsAppCheckoutMessageParser.cashDenomination(message);
        if (denomination == null || denomination.signum() <= 0) {
            return "Indica una sola cantidad válida, por ejemplo: 200 o 500.";
        }
        try {
            CartSnapshot snapshot = cartSnapshotService.readActiveCart(phoneNumber);
            if (denomination.compareTo(snapshot.total()) < 0) {
                return "La cantidad indicada es menor al total de $" + money(snapshot.total())
                        + ". Indica con cuánto efectivo pagarás.";
            }
            intentRouter.route(phoneNumber, new CheckoutIntent.ProvideCashDenomination(denomination));
            return readySummary(phoneNumber);
        } catch (InvalidConversationTransitionException exception) {
            return "Indica una cantidad válida con máximo dos decimales.";
        }
    }

    private String handleFinalConfirmation(String phoneNumber, ConversationSession session, String message) {
        if (WhatsAppCheckoutMessageParser.isNegative(message)) {
            intentRouter.route(phoneNumber, new CheckoutIntent.CancelCheckout());
            return "De acuerdo, no confirmé la orden. Tu carrito se conserva por si deseas hacer cambios.";
        }
        if (!WhatsAppCheckoutMessageParser.isAffirmative(message)) {
            return "Responde 'confirmar' para crear la orden o 'cancelar' para detener el proceso.";
        }
        try {
            CartSnapshot snapshot = cartSnapshotService.readActiveCart(phoneNumber);
            CheckoutCompletionResult result = orderService.completeCheckout(new CheckoutCompletionCommand(
                    phoneNumber, snapshot.cartId(), OrderSource.WHATSAPP_AI));
            LOGGER.info("whatsapp_checkout_completed requestId={} orderId={} outcome={}",
                    MDC.get("requestId"), result.orderId(), result.outcome());
            if (session.getPaymentMethod() == PaymentMethod.TRANSFER) {
                return "¡Orden #" + result.orderId()
                        + " confirmada! Quedó en Nuevos pedidos, pendiente de validar tu transferencia.";
            }
            return "¡Orden #" + result.orderId()
                    + " confirmada! La envié a Nuevos pedidos para que Cocina la acepte.";
        } catch (RuntimeException exception) {
            logFailure("whatsapp_checkout_completion_failed", exception);
            return "No pude confirmar la orden. No se creó ningún pedido; intenta nuevamente.";
        }
    }

    private String readySummary(String phoneNumber) {
        ConversationSession session = sessionService.findSession(phoneNumber).orElseThrow();
        CartSnapshot snapshot = cartSnapshotService.readActiveCart(phoneNumber);
        String fulfillment = session.getFulfillmentType() == FulfillmentType.DELIVERY
                ? "Entrega: " + session.getDeliveryAddress()
                : "Recoge: " + session.getPickupName();
        String payment = switch (session.getPaymentMethod()) {
            case CASH -> "Efectivo: $" + money(session.getCashDenomination());
            case TRANSFER -> "Transferencia: comprobante recibido";
            case CARD -> "Tarjeta al recoger";
        };
        return "Resumen final\n" + fulfillment + "\nPago: " + payment + "\nTotal: $" + money(snapshot.total())
                + " MXN\n\nResponde 'confirmar' para crear la orden o 'cancelar' para detener el proceso.";
    }

    private String formatCart(CartSnapshot snapshot) {
        StringBuilder response = new StringBuilder("Resumen de tu carrito:");
        for (CartLineSnapshot line : snapshot.items()) {
            response.append("\n- ").append(line.quantity()).append("x ").append(line.dishName())
                    .append(" = $").append(money(line.lineTotal()));
        }
        return response.append("\nTotal: $").append(money(snapshot.total())).append(" MXN").toString();
    }

    private String paymentPrompt(boolean pickup) {
        return pickup
                ? "¿Cómo pagarás? Elige efectivo, transferencia o tarjeta al recoger."
                : "¿Cómo pagarás? Elige efectivo o transferencia.";
    }

    private String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private void logFailure(String event, RuntimeException exception) {
        LOGGER.warn("{} requestId={} reason={}", event, MDC.get("requestId"),
                exception.getClass().getSimpleName());
    }
}
