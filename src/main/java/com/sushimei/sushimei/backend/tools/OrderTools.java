package com.sushimei.sushimei.backend.tools;

import com.sushimei.sushimei.backend.agent.AiToolSafetyException;
import com.sushimei.sushimei.backend.agent.AiToolSafetyGuard;
import com.sushimei.sushimei.backend.checkout.ActiveCartNotFoundException;
import com.sushimei.sushimei.backend.checkout.EmptyCartException;
import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.MonetaryCompatibilityException;
import com.sushimei.sushimei.backend.checkout.InvalidCartItemException;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.CartService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "sushimei.features.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);
    private static final String CHECKOUT_DATA_FAILURE_RESPONSE =
            "No se pudo procesar el carrito en este momento. Intenta nuevamente o solicita ayuda del restaurante.";

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final AiToolSafetyGuard toolSafetyGuard;
    private final AiMenuItemResolver menuItemResolver;

    @Autowired
    public OrderTools(OrderRepository orderRepository,
                      CartService cartService,
                      AiToolSafetyGuard toolSafetyGuard,
                      AiMenuItemResolver menuItemResolver) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.toolSafetyGuard = toolSafetyGuard;
        this.menuItemResolver = menuItemResolver;
    }

    @Tool("Agrega un platillo al carrito solo cuando el cliente pida claramente agregar ese producto. "
            + "No la uses para saludos, preguntas de menú o precios. Para varios productos identificables, úsala una vez por cada producto.")
    public String addDishToCart(
            @P("El número de teléfono del cliente") String phoneNumber,
            @P("El nombre exacto del platillo que el usuario quiere ordenar") String dishName,
            @P("La cantidad numérica de platillos") int quantity) {

        try {
            toolSafetyGuard.requireAddAllowed(dishName, quantity);
            ResolvedMenuItem resolvedItem = menuItemResolver.resolveExact(dishName);
            return addServerResolvedDishToCart(phoneNumber, resolvedItem, quantity);
        } catch (AiToolSafetyException exception) {
            toolSafetyGuard.recordAddBlocked();
            return toolSafetyFailureResponse("ADD_CART_ITEM", exception);
        } catch (AiMenuItemResolutionException exception) {
            toolSafetyGuard.recordAddBlocked();
            log.warn("AI tool outcome=ADD_CART_ITEM result=BLOCKED reason=CATALOG_ITEM_NOT_RESOLVED");
            return "No agregues ese producto todavía. Pide al cliente el nombre y la presentación exactos del menú.";
        } catch (MonetaryCompatibilityException | InvalidCartItemException | IllegalArgumentException | ArithmeticException exception) {
            toolSafetyGuard.recordAddFailed();
            return checkoutDataFailureResponse("ADD_CART_ITEM", exception);
        }
    }

    /** Internal deterministic path for catalog items resolved by the server from the current message. */
    public String addServerResolvedDishToCart(String phoneNumber, ResolvedMenuItem resolvedItem, int quantity) {
        try {
            cartService.addItem(phoneNumber, resolvedItem.name(), quantity, resolvedItem.unitPrice().doubleValue());
            String cartContents = cartService.getCartContents(phoneNumber);
            String response = "\u00a1Listo! Agregu\u00e9 " + quantity + " x " + resolvedItem.name()
                    + " a tu carrito.\n" + cartContents;
            toolSafetyGuard.recordAddSucceeded(resolvedItem.name(), quantity, cartContents);
            log.info("AI tool outcome=ADD_CART_ITEM result=SUCCESS source=SERVER_RESOLVED");
            return response;
        } catch (MonetaryCompatibilityException | InvalidCartItemException | IllegalArgumentException | ArithmeticException exception) {
            toolSafetyGuard.recordAddFailed();
            return checkoutDataFailureResponse("ADD_CART_ITEM", exception);
        }
    }

    /** Compatibility helper for direct callers compiled against the former AI-price signature. */
    public String addDishToCart(String phoneNumber, String dishName, int quantity, double ignoredUnitPrice) {
        return addDishToCart(phoneNumber, dishName, quantity);
    }

    @Tool("Consulta el contenido actual y el total del carrito. Úsala solo cuando el cliente pregunte qué lleva, "
            + "qué tiene en el carrito o cuánto es. No la uses para saludos, preguntas del menú ni automáticamente después de agregar o quitar productos.")
    public String checkCart(
            @P("El número de teléfono del cliente") String phoneNumber) {

        try {
            toolSafetyGuard.requireCartQueryAllowed();
            String cartContents = cartService.getCartContents(phoneNumber);
            toolSafetyGuard.recordCartQuerySucceeded(cartContents);
            log.info("AI tool outcome=CHECK_CART result=SUCCESS");
            return cartContents;
        } catch (AiToolSafetyException exception) {
            return toolSafetyFailureResponse("CHECK_CART", exception);
        } catch (MonetaryCompatibilityException | InvalidCartItemException | IllegalArgumentException | ArithmeticException exception) {
            return checkoutDataFailureResponse("CHECK_CART", exception);
        }
    }

    @Tool("Elimina o resta un platillo del carrito solo cuando el cliente pida claramente quitar, eliminar, cancelar o restar ese producto.")
    public String removeDishFromCart(
            @P("El número de teléfono del cliente") String phoneNumber,
            @P("El nombre EXACTO del platillo que el usuario quiere quitar, tal cual como aparece en la consulta del carrito") String dishName,
            @P("La cantidad numérica de platillos que desea restar o quitar") int quantity) {

        try {
            toolSafetyGuard.requireRemoveAllowed(dishName);
            String result = cartService.removeItem(phoneNumber, dishName, quantity);
            if (result != null && result.startsWith("Error interno:")) {
                toolSafetyGuard.recordRemoveFailed();
                log.warn("AI tool outcome=REMOVE_CART_ITEM result=FAILURE reason=ITEM_NOT_FOUND");
                return result;
            }
            String response = "\u00a1Listo! Quit\u00e9 " + quantity + " x " + dishName + " de tu carrito.\n" + result;
            toolSafetyGuard.recordRemoveSucceeded(dishName, quantity, result);
            log.info("AI tool outcome=REMOVE_CART_ITEM result=SUCCESS");
            return response;
        } catch (AiToolSafetyException exception) {
            toolSafetyGuard.recordRemoveBlocked();
            return toolSafetyFailureResponse("REMOVE_CART_ITEM", exception);
        } catch (MonetaryCompatibilityException | InvalidCartItemException | IllegalArgumentException | ArithmeticException exception) {
            toolSafetyGuard.recordRemoveFailed();
            return checkoutDataFailureResponse("REMOVE_CART_ITEM", exception);
        }
    }

    @Tool("Operación heredada para finalizar una orden. No debe ser llamada por el agente conversacional hasta que exista un servicio determinista de finalización de pedidos.")
    public String confirmOrder(
            @P("El teléfono del cliente. (Ej. 524771234567)")
            String phoneNumber,

            @P("OBLIGATORIO: Tipo de entrega. Solo puede ser 'DOMICILIO' o 'SUCURSAL'. NO ASUMAS ESTE DATO.")
            String deliveryType,

            @P("OBLIGATORIO: Si es domicilio, la dirección exacta proporcionada por el cliente. Si es sucursal, el nombre de quien recoge. NO INVENTES ESTE DATO.")
            String deliveryAddress,

            @P("OBLIGATORIO: Detalles del pago. Ej. 'Efectivo billete 500' o 'Transferencia (Comprobante pendiente)'. TIENES QUE PREGUNTARLO ANTES.")
            String paymentNotes) {

        try {
            toolSafetyGuard.requireLegacyOrderConfirmationBlocked();
        } catch (AiToolSafetyException exception) {
            toolSafetyGuard.recordConfirmationBlocked();
            return toolSafetyFailureResponse("CONFIRM_ORDER", exception);
        }

        if (deliveryType == null || deliveryType.trim().isEmpty()) {
            return "ERROR AL CONFIRMAR: No enviaste el tipo de entrega. Pregúntale al cliente si es para DOMICILIO o SUCURSAL y vuelve a intentarlo.";
        }

        if (deliveryAddress == null || deliveryAddress.trim().isEmpty() || deliveryAddress.length() < 5) {
            return "ERROR AL CONFIRMAR: La dirección o el nombre están vacíos o son muy cortos. PREGÚNTALE DIRECTAMENTE AL CLIENTE por su dirección exacta o nombre y espera su respuesta.";
        }

        if (paymentNotes == null || paymentNotes.trim().isEmpty() || paymentNotes.length() < 5) {
            return "ERROR: Faltan los detalles de pago. Pregúntale al cliente.";
        }

        String paymentLower = paymentNotes.toLowerCase();

        // EL NUEVO CANDADO PARA TRANSFERENCIAS
        if (paymentLower.contains("transferencia")) {
            // Si dice transferencia, pero la nota no confirma que recibió la imagen, la bloqueamos
            if (!paymentLower.contains("recibid") && !paymentLower.contains("imagen") && !paymentLower.contains("foto")) {
                return "ERROR AL CONFIRMAR: El cliente dijo transferencia pero NO HAS RECIBIDO LA FOTO DEL COMPROBANTE. Dile al cliente que estás esperando la imagen para poder continuar.";
            }
        }

        // Si es efectivo, exigimos que venga un número (el billete)
        if (paymentLower.contains("efectivo")) {
            if (!paymentLower.matches(".*\\d.*")) {
                return "ERROR AL CONFIRMAR: El cliente paga en efectivo pero NO PREGUNTASTE el billete. Pregúntale con qué billete va a pagar.";
            }
        }

        ParallelMoney cartTotal;
        String cartDetails;
        try {
            cartTotal = cartService.getCartTotalForOrder(phoneNumber);
            cartDetails = cartService.getCartContents(phoneNumber);
        } catch (ActiveCartNotFoundException | EmptyCartException exception) {
            return "Error: El carrito est\u00e1 vac\u00edo. Pide al cliente que agregue platillos antes de confirmar.";
        } catch (MonetaryCompatibilityException | InvalidCartItemException | IllegalArgumentException | ArithmeticException exception) {
            return checkoutDataFailureResponse("CONFIRM_ORDER", exception);
        }

        // 1. Crear el registro oficial
        OrderRecord newOrder = new OrderRecord();
        newOrder.setPhoneNumber(phoneNumber);
        newOrder.setDeliveryType(deliveryType); // Guardamos el tipo de logística
        newOrder.setDeliveryAddress(deliveryType.equalsIgnoreCase("SUCURSAL") ? "Recoge en Sucursal" : deliveryAddress);
        newOrder.setPaymentNotes(paymentNotes);
        newOrder.setOrderDetails(cartDetails);
        newOrder.setTotalAmount(cartTotal.legacyAmount());
        newOrder.setTotalAmountAmount(cartTotal.numericAmount());
        newOrder.setStatus("PENDING");
        newOrder.setCreatedAt(LocalDateTime.now());

        OrderRecord savedOrder = orderRepository.save(newOrder);

        // 2. Cerrar el carrito actual del cliente
        cartService.clearCart(phoneNumber);

        log.info("AI tool outcome=CONFIRM_ORDER result=SUCCESS");

        // 3. Respuesta dinámica dependiendo del tipo de entrega
        if (deliveryType.equalsIgnoreCase("SUCURSAL")) {
            return "La orden fue guardada. El ticket es #" + savedOrder.getId() +
                    ". Dile al cliente que su pedido estará listo en aproximadamente 25 minutos y lo esperamos en la sucursal para su pago y entrega.";
        } else {
            return "La orden fue guardada. El ticket es #" + savedOrder.getId() +
                    ". Dile al cliente que su pedido ya está en preparación y saldrá a su domicilio en aproximadamente 35 a 45 minutos.";
        }
    }

    private String toolSafetyFailureResponse(String toolName, AiToolSafetyException exception) {
        log.warn("AI tool outcome={} result=BLOCKED reason={}", toolName, exception.getReason());
        return switch (exception.getReason()) {
            case ADD_NOT_EXPLICITLY_REQUESTED -> "No agregues productos todavía. Pide al cliente que indique el nombre exacto del platillo o bebida.";
            case REMOVE_NOT_EXPLICITLY_REQUESTED -> "No quites productos todavía. Pide al cliente que indique el nombre exacto del producto que desea quitar.";
            case CART_QUERY_NOT_REQUESTED, CART_QUERY_ALREADY_PERFORMED -> "El cliente no solicitó consultar el carrito. Responde sin usar herramientas.";
            case LEGACY_ORDER_CONFIRMATION_DISABLED -> "No confirmes ni declares una orden creada. La finalización de pedidos se procesa por un flujo separado.";
        };
    }

    private String checkoutDataFailureResponse(String toolName, RuntimeException exception) {
        if (exception instanceof MonetaryCompatibilityException monetaryFailure) {
            log.warn("AI tool outcome={} result=FAILURE reason={}", toolName, monetaryFailure.getReason());
        } else if (exception instanceof InvalidCartItemException cartItemFailure) {
            log.warn("AI tool outcome={} result=FAILURE reason={}", toolName, cartItemFailure.getReason());
        } else {
            log.warn("AI tool outcome={} result=FAILURE reason={}", toolName, exception.getClass().getSimpleName());
        }
        return CHECKOUT_DATA_FAILURE_RESPONSE;
    }
}
