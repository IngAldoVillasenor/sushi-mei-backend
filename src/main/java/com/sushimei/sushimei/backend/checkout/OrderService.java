package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.conversation.ConversationSession;
import com.sushimei.sushimei.backend.conversation.ConversationSessionRepository;
import com.sushimei.sushimei.backend.conversation.ConversationStateMachine;
import com.sushimei.sushimei.backend.conversation.FulfillmentType;
import com.sushimei.sushimei.backend.conversation.PaymentMethod;
import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.repository.CartRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Internal deterministic checkout core. Trusted adapters may invoke this atomic boundary,
 * but raw customer text and AI tools never write orders directly.
 */
@Service
public class OrderService {

    private static final String OPEN_CART_STATUS = "OPEN";
    private static final String CLOSED_CART_STATUS = "CLOSED";
    private static final String LEGACY_DELIVERY_TYPE = "DOMICILIO";
    private static final String LEGACY_PICKUP_TYPE = "SUCURSAL";

    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final ConversationSessionRepository conversationSessionRepository;
    private final CartSnapshotService cartSnapshotService;
    private final ConversationStateMachine conversationStateMachine;
    private final ParallelMoneyResolver parallelMoneyResolver;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;
    private final LegacyOrderDetailsFormatter legacyOrderDetailsFormatter = new LegacyOrderDetailsFormatter();

    public OrderService(CartRepository cartRepository,
                        OrderRepository orderRepository,
                        ConversationSessionRepository conversationSessionRepository,
                        CartSnapshotService cartSnapshotService,
                        ConversationStateMachine conversationStateMachine,
                        ParallelMoneyResolver parallelMoneyResolver,
                        CheckoutMoney checkoutMoney,
                        Clock clock) {
        this.cartRepository = Objects.requireNonNull(cartRepository, "cartRepository must not be null");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.conversationSessionRepository = Objects.requireNonNull(conversationSessionRepository,
                "conversationSessionRepository must not be null");
        this.cartSnapshotService = Objects.requireNonNull(cartSnapshotService, "cartSnapshotService must not be null");
        this.conversationStateMachine = Objects.requireNonNull(conversationStateMachine,
                "conversationStateMachine must not be null");
        this.parallelMoneyResolver = Objects.requireNonNull(parallelMoneyResolver,
                "parallelMoneyResolver must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Completes exactly one already-selected cart in one short database
     * transaction. No external, AI, filesystem, or HTTP work occurs here.
     */
    @Transactional
    public CheckoutCompletionResult completeCheckout(CheckoutCompletionCommand command) {
        CompletionRequest request = validateCommand(command);

        OrderRecord existingBeforeLock = orderRepository.findBySourceCartId(request.sourceCartId()).orElse(null);
        if (existingBeforeLock != null) {
            return existingResult(existingBeforeLock, request);
        }

        Cart sourceCart = cartRepository.findByIdForUpdate(request.sourceCartId())
                .orElseThrow(() -> failure(CheckoutCompletionFailureReason.CART_NOT_FOUND));

        OrderRecord existingAfterLock = orderRepository.findBySourceCartId(request.sourceCartId()).orElse(null);
        if (existingAfterLock != null) {
            return existingResult(existingAfterLock, request);
        }

        validateExactCart(sourceCart, request.phoneNumber());
        CartSnapshot snapshot = cartSnapshotService.snapshotOf(sourceCart);
        ConversationSession session = conversationSessionRepository.findById(request.phoneNumber())
                .orElseThrow(() -> failure(CheckoutCompletionFailureReason.CONVERSATION_SESSION_NOT_FOUND));
        validateCashDenomination(session, snapshot.total());
        Instant now = clock.instant();

        OrderRecord order = buildOrder(request, snapshot, session, now);
        OrderRecord savedOrder = orderRepository.save(order);
        sourceCart.setStatus(CLOSED_CART_STATUS);
        conversationStateMachine.confirmCheckout(session, now);

        return new CheckoutCompletionResult(CheckoutCompletionOutcome.CREATED, requireOrderId(savedOrder));
    }

    private void validateCashDenomination(ConversationSession session, BigDecimal total) {
        if (session.getPaymentMethod() == PaymentMethod.CASH
                && (session.getCashDenomination() == null || session.getCashDenomination().compareTo(total) < 0)) {
            throw failure(CheckoutCompletionFailureReason.CASH_DENOMINATION_INSUFFICIENT);
        }
    }

    private CheckoutCompletionResult existingResult(OrderRecord existing, CompletionRequest request) {
        if (!request.phoneNumber().equals(existing.getPhoneNumber())) {
            throw failure(CheckoutCompletionFailureReason.IDEMPOTENCY_PHONE_MISMATCH);
        }
        if (existing.getOrderSource() != request.orderSource()
                || !isStructurallyCompatible(existing, request.sourceCartId())) {
            throw failure(CheckoutCompletionFailureReason.IDEMPOTENCY_INCOMPATIBLE_ORDER);
        }
        return new CheckoutCompletionResult(CheckoutCompletionOutcome.ALREADY_COMPLETED, requireOrderId(existing));
    }

    private CompletionRequest validateCommand(CheckoutCompletionCommand command) {
        if (command == null || command.phoneNumber() == null || command.phoneNumber().isBlank()
                || command.sourceCartId() == null || command.sourceCartId() <= 0 || command.orderSource() == null) {
            throw failure(CheckoutCompletionFailureReason.INVALID_COMMAND);
        }
        return new CompletionRequest(command.phoneNumber().trim(), command.sourceCartId(), command.orderSource());
    }

    private void validateExactCart(Cart sourceCart, String phoneNumber) {
        if (sourceCart.getId() == null) {
            throw failure(CheckoutCompletionFailureReason.CART_NOT_FOUND);
        }
        if (!phoneNumber.equals(sourceCart.getPhoneNumber())) {
            throw failure(CheckoutCompletionFailureReason.CART_PHONE_MISMATCH);
        }
        if (!OPEN_CART_STATUS.equals(sourceCart.getStatus())) {
            throw failure(CheckoutCompletionFailureReason.CART_NOT_OPEN);
        }
    }

    private OrderRecord buildOrder(CompletionRequest request,
                                   CartSnapshot snapshot,
                                   ConversationSession session,
                                   Instant now) {
        ParallelMoney legacyTotal = parallelMoneyResolver.forWriteFromExact(snapshot.total());
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber(request.phoneNumber());
        order.setSourceCartId(snapshot.cartId());
        order.setOrderSource(request.orderSource());
        copyFulfillment(session, order);
        copyPayment(session, order);
        order.setOrderDetails(legacyOrderDetailsFormatter.format(snapshot));
        order.setTotalAmountAmount(snapshot.total());
        order.setTotalAmount(legacyTotal.legacyAmount());
        order.setStatus(session.getPaymentMethod() == PaymentMethod.TRANSFER
                ? OrderLifecycleStatus.PENDING_VALIDATION.persistedValue()
                : OrderLifecycleStatus.PENDING.persistedValue());
        order.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));

        int linePosition = 1;
        for (CartLineSnapshot line : snapshot.items()) {
            order.addOrderLine(OrderLineRecord.create(
                    line.cartItemId(),
                    linePosition++,
                    line.dishName(),
                    line.quantity(),
                    line.unitPrice(),
                    line.lineTotal()));
        }
        return order;
    }

    private void copyFulfillment(ConversationSession session, OrderRecord order) {
        FulfillmentType fulfillmentType = session.getFulfillmentType();
        if (fulfillmentType == FulfillmentType.DELIVERY) {
            order.setFulfillmentType(OrderFulfillmentType.DELIVERY);
            order.setDeliveryType(LEGACY_DELIVERY_TYPE);
            order.setDeliveryAddress(session.getDeliveryAddress());
            return;
        }
        if (fulfillmentType == FulfillmentType.PICKUP) {
            order.setFulfillmentType(OrderFulfillmentType.PICKUP);
            order.setDeliveryType(LEGACY_PICKUP_TYPE);
            order.setPickupName(session.getPickupName());
        }
    }

    private void copyPayment(ConversationSession session, OrderRecord order) {
        PaymentMethod paymentMethod = session.getPaymentMethod();
        if (paymentMethod == PaymentMethod.CASH) {
            order.setPaymentMethod(OrderPaymentMethod.CASH);
            order.setCashDenomination(session.getCashDenomination());
            order.setPaymentNotes("Efectivo");
            return;
        }
        if (paymentMethod == PaymentMethod.TRANSFER) {
            order.setPaymentMethod(OrderPaymentMethod.TRANSFER);
            order.setTransferReceiptPath(session.getTransferReceiptPath());
            order.setPaymentNotes("Transferencia (comprobante recibido)");
            return;
        }
        if (paymentMethod == PaymentMethod.CARD) {
            order.setPaymentMethod(OrderPaymentMethod.CARD);
            order.setPaymentNotes("Tarjeta");
        }
    }

    private boolean isStructurallyCompatible(OrderRecord order, Long sourceCartId) {
        if (!sourceCartId.equals(order.getSourceCartId())
                || order.getOrderSource() == null
                || order.getFulfillmentType() == null
                || order.getPaymentMethod() == null
                || order.getTotalAmountAmount() == null) {
            return false;
        }
        try {
            BigDecimal total = parallelMoneyResolver.resolve(order.getTotalAmountAmount(), order.getTotalAmount());
            List<OrderLineRecord> lines = order.getOrderLines();
            if (lines.isEmpty()) {
                return false;
            }

            BigDecimal calculatedTotal = BigDecimal.ZERO.setScale(CheckoutMoney.SCALE);
            int expectedPosition = 1;
            for (OrderLineRecord line : lines) {
                if (line.getSourceCartItemId() == null || line.getLinePosition() != expectedPosition++) {
                    return false;
                }
                BigDecimal lineTotal = checkoutMoney.calculateLineTotal(line.getQuantity(), line.getUnitPriceAmount());
                if (lineTotal.compareTo(line.getLineTotalAmount()) != 0) {
                    return false;
                }
                calculatedTotal = calculatedTotal.add(lineTotal);
            }
            if (checkoutMoney.normalizeNumericAmount(calculatedTotal).compareTo(total) != 0) {
                return false;
            }
            return hasCompatibleFulfillment(order) && hasCompatiblePayment(order);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasCompatibleFulfillment(OrderRecord order) {
        return switch (order.getFulfillmentType()) {
            case DELIVERY -> isPresent(order.getDeliveryAddress()) && order.getPickupName() == null;
            case PICKUP -> isPresent(order.getPickupName()) && order.getDeliveryAddress() == null;
        };
    }

    private boolean hasCompatiblePayment(OrderRecord order) {
        return switch (order.getPaymentMethod()) {
            case CASH -> isPositiveExact(order.getCashDenomination()) && order.getTransferReceiptPath() == null;
            case TRANSFER -> isPresent(order.getTransferReceiptPath()) && order.getCashDenomination() == null;
            case CARD -> order.getFulfillmentType() == OrderFulfillmentType.PICKUP
                    && order.getCashDenomination() == null
                    && order.getTransferReceiptPath() == null;
        };
    }

    private boolean isPositiveExact(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNumericAmount(amount) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private Long requireOrderId(OrderRecord order) {
        if (order.getId() == null) {
            throw failure(CheckoutCompletionFailureReason.IDEMPOTENCY_INCOMPATIBLE_ORDER);
        }
        return order.getId();
    }

    private CheckoutCompletionException failure(CheckoutCompletionFailureReason reason) {
        return new CheckoutCompletionException(reason);
    }

    private record CompletionRequest(String phoneNumber, Long sourceCartId, com.sushimei.sushimei.backend.entity.OrderSource orderSource) {
    }
}
