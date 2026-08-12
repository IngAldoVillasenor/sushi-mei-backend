package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.order.ActiveOrderResponse;
import com.sushimei.sushimei.backend.order.LegacyOrderRejectionResult;
import com.sushimei.sushimei.backend.order.OrderLifecycleError;
import com.sushimei.sushimei.backend.order.OrderLifecycleException;
import com.sushimei.sushimei.backend.order.OrderLifecycleService;
import com.sushimei.sushimei.backend.service.CartService;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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

    private final AiConversationService aiConversationService;
    private final WhatsAppService whatsAppService;
    private final CartService cartService;
    private final OrderLifecycleService orderLifecycleService;

    public OrderController(AiConversationService aiConversationService,
                           WhatsAppService whatsAppService,
                           CartService cartService,
                           OrderLifecycleService orderLifecycleService) {
        this.aiConversationService = aiConversationService;
        this.whatsAppService = whatsAppService;
        this.cartService = cartService;
        this.orderLifecycleService = orderLifecycleService;
    }

    @GetMapping("/active")
    public ResponseEntity<List<ActiveOrderResponse>> getActiveOrders() {
        return ResponseEntity.ok(orderLifecycleService.activeOrders());
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<String> completeOrder(@PathVariable Long id) {
        orderLifecycleService.complete(id);
        return ResponseEntity.ok("Orden #" + id + " despachada exitosamente.");
    }

    /**
     * Legacy orchestration only. The cancellation transition commits before cart/AI/WhatsApp work,
     * which remains deliberately non-atomic and is not a POS rejection workflow.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<String> rejectOrder(@PathVariable Long id, @RequestBody Map<String, String> body) {
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
        String aiResponse = aiConversationService.chat(rejected.phoneNumber(), rejected.phoneNumber(), promptParaIA);
        whatsAppService.sendMessage(rejected.phoneNumber(), aiResponse);

        return ResponseEntity.ok("Orden rechazada. Notificando al cliente vía WhatsApp.");
    }

    @PutMapping("/{id}/prepare")
    public ResponseEntity<String> prepareOrder(@PathVariable Long id) {
        orderLifecycleService.prepare(id);
        return ResponseEntity.ok("Orden #" + id + " enviada a cocina.");
    }

    @PutMapping("/{id}/ready")
    public ResponseEntity<String> readyOrder(@PathVariable Long id) {
        orderLifecycleService.ready(id);
        return ResponseEntity.ok("Orden #" + id + " lista para entrega.");
    }

    @PutMapping("/{id}/validate-payment")
    public ResponseEntity<String> validatePayment(@PathVariable Long id) {
        orderLifecycleService.validatePayment(id);
        return ResponseEntity.ok("Pago validado para la orden #" + id);
    }
}
