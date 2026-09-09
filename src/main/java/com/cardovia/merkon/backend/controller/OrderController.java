package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.agent.AiConversationService;
import com.cardovia.merkon.backend.order.ActiveOrderResponse;
import com.cardovia.merkon.backend.order.LegacyOrderRejectionResult;
import com.cardovia.merkon.backend.order.OrderLifecycleError;
import com.cardovia.merkon.backend.order.OrderLifecycleException;
import com.cardovia.merkon.backend.order.OrderLifecycleService;
import com.cardovia.merkon.backend.order.OrderPaymentCollectionRequest;
import com.cardovia.merkon.backend.order.OrderPaymentCollectionResponse;
import com.cardovia.merkon.backend.order.OrderVoidRequest;
import com.cardovia.merkon.backend.order.OrderVoidResponse;
import com.cardovia.merkon.backend.service.CartService;
import com.cardovia.merkon.backend.security.AuthenticatedLegacyBusinessGuard;
import com.cardovia.merkon.backend.service.WhatsAppService;
import com.cardovia.merkon.backend.security.TrustedBusinessContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final Optional<AiConversationService> aiConversationService;
    private final Optional<WhatsAppService> whatsAppService;
    private final CartService cartService;
    private final OrderLifecycleService orderLifecycleService;
    private final TrustedBusinessContext trustedBusinessContext;
    private final AuthenticatedLegacyBusinessGuard authenticatedLegacyBusinessGuard;

    public OrderController(Optional<AiConversationService> aiConversationService,
                           Optional<WhatsAppService> whatsAppService,
                           CartService cartService,
                           OrderLifecycleService orderLifecycleService,
                           TrustedBusinessContext trustedBusinessContext,
                           AuthenticatedLegacyBusinessGuard authenticatedLegacyBusinessGuard) {
        this.aiConversationService = aiConversationService;
        this.whatsAppService = whatsAppService;
        this.cartService = cartService;
        this.orderLifecycleService = orderLifecycleService;
        this.trustedBusinessContext = trustedBusinessContext;
        this.authenticatedLegacyBusinessGuard = authenticatedLegacyBusinessGuard;
    }

    @GetMapping("/active")
    public ResponseEntity<List<ActiveOrderResponse>> getActiveOrders(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(orderLifecycleService.activeOrders(businessId(jwt)));
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<String> completeOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        orderLifecycleService.complete(businessId(jwt), id);
        return ResponseEntity.ok("Orden #" + id + " despachada exitosamente.");
    }

    @PutMapping("/{id}/collect-payment")
    public ResponseEntity<OrderPaymentCollectionResponse> collectPayment(@PathVariable Long id,
                                                                          @AuthenticationPrincipal Jwt jwt,
                                                                          @RequestBody OrderPaymentCollectionRequest request) {
        return ResponseEntity.ok(orderLifecycleService.collectPayment(businessId(jwt), id, userId(jwt), request));
    }

    /**
     * Legacy orchestration only. The cancellation transition commits before cart/AI/WhatsApp work,
     * which remains deliberately non-atomic and is not a POS rejection workflow.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<String> rejectOrder(@PathVariable Long id,
                                              @AuthenticationPrincipal Jwt jwt,
                                              @RequestBody Map<String, String> body) {
        authenticatedLegacyBusinessGuard.requireLegacyBusiness(jwt);
        if (aiConversationService.isEmpty() || whatsAppService.isEmpty()) {
            return ResponseEntity.status(409).body("El flujo heredado de rechazo no está habilitado en este runtime.");
        }

        LegacyOrderRejectionResult rejected;
        try {
            rejected = orderLifecycleService.rejectForLegacyClarification(id);
        } catch (OrderLifecycleException exception) {
            if (exception.getError() == OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED) {
                return ResponseEntity.status(409).body("La orden POS requiere el flujo operativo correspondiente.");
            }
            throw exception;
        }

        cartService.reopenCart(rejected.phoneNumber());

        String reason = body.get("reason");
        String promptParaIA = "INSTRUCCIÓN DEL SISTEMA: La cocina acaba de rechazar el pedido del cliente por esta razón: '"
                + reason + "'. Discúlpate amablemente con el cliente, explícale la razón, infórmale que su carrito sigue "
                + "guardado con los demás productos y pregúntale por qué desea sustituir el producto faltante.";
        String aiResponse = aiConversationService.orElseThrow()
                .chat(rejected.phoneNumber(), rejected.phoneNumber(), promptParaIA);
        whatsAppService.orElseThrow().sendMessage(rejected.phoneNumber(), aiResponse);

        return ResponseEntity.ok("Orden rechazada. Notificando al cliente vía WhatsApp.");
    }

    @PutMapping("/{id}/prepare")
    public ResponseEntity<String> prepareOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        orderLifecycleService.prepare(businessId(jwt), id);
        return ResponseEntity.ok("Orden #" + id + " enviada a cocina.");
    }

    @PutMapping("/{id}/ready")
    public ResponseEntity<String> readyOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        orderLifecycleService.ready(businessId(jwt), id);
        return ResponseEntity.ok("Orden #" + id + " lista para entrega.");
    }

    @PutMapping("/{id}/void")
    public ResponseEntity<OrderVoidResponse> voidOrder(@PathVariable Long id,
                                                        @AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody OrderVoidRequest request) {
        return ResponseEntity.ok(orderLifecycleService.voidOrder(businessId(jwt), id, userId(jwt), request));
    }

    @PutMapping("/{id}/validate-payment")
    public ResponseEntity<String> validatePayment(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        orderLifecycleService.validatePayment(businessId(jwt), id);
        return ResponseEntity.ok("Pago validado para la orden #" + id);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    private Long businessId(Jwt jwt) {
        return trustedBusinessContext.requireBusinessId(jwt);
    }
}
