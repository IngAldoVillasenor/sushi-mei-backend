package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.CartService;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepository;

    private final AiConversationService aiConversationService;

    private final WhatsAppService whatsAppService;

    private final CartService cartService;

    public OrderController(OrderRepository orderRepository, AiConversationService aiConversationService, WhatsAppService whatsAppService, CartService cartService) {
        this.orderRepository = orderRepository;
        this.aiConversationService = aiConversationService;
        this.whatsAppService = whatsAppService;
        this.cartService = cartService;
    }

    // 1. Endpoint para la pantalla de la cocina (Lista las órdenes pendientes)
    @GetMapping("/active")
    public ResponseEntity<List<OrderRecord>> getActiveOrders() {
        // Pedimos a la base de datos que traiga tanto las pendientes como las que están preparándose
        List<String> activeStatuses = Arrays.asList("PENDING_VALIDATION","PENDING", "PREPARING");
        List<OrderRecord> activeOrders = orderRepository.findByStatusInOrderByCreatedAtAsc(activeStatuses);

        return ResponseEntity.ok(activeOrders);
    }

    // 2. Endpoint para que el cocinero marque la orden como lista/despachada
    @PutMapping("/{id}/complete")
    public ResponseEntity<String> completeOrder(@PathVariable Long id) {
        Optional<OrderRecord> orderOpt = orderRepository.findById(id);

        if (orderOpt.isPresent()) {
            OrderRecord order = orderOpt.get();
            order.setStatus("COMPLETED"); // Cambia el estado para que desaparezca de la pantalla
            orderRepository.save(order);
            return ResponseEntity.ok("Orden #" + id + " despachada exitosamente.");
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<String> rejectOrder(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        Optional<OrderRecord> orderOpt = orderRepository.findById(id);

        if (orderOpt.isPresent()) {
            OrderRecord order = orderOpt.get();

            // 1. Cancelamos la orden actual
            order.setStatus("CANCELLED_CLARIFICATION");
            orderRepository.save(order);

            // 2. Re-abrimos el carrito (Cambiando el status en la tabla cart de CLOSED a OPEN)
            cartService.reopenCart(order.getPhoneNumber());

            // 3. Le pasamos el contexto a la IA y le pedimos que le hable al cliente
            String promptParaIA = "INSTRUCCIÓN DEL SISTEMA: La cocina acaba de rechazar el pedido del cliente por esta razón: '" + reason + "'. " +
                    "Discúlpate amablemente con el cliente, explícale la razón, infórmale que su carrito sigue guardado con los demás productos y pregúntale por qué desea sustituir el producto faltante.";

            // La IA genera la disculpa y sugerencia
            String aiResponse = aiConversationService.chat(order.getPhoneNumber(), order.getPhoneNumber(), promptParaIA);

            // Se la mandamos proactivamente por WhatsApp
            whatsAppService.sendMessage(order.getPhoneNumber(), aiResponse);

            return ResponseEntity.ok("Orden rechazada. Notificando al cliente vía WhatsApp.");
        }
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}/prepare")
    public ResponseEntity<String> prepareOrder(@PathVariable Long id) {
        Optional<OrderRecord> orderOpt = orderRepository.findById(id);

        if (orderOpt.isPresent()) {
            OrderRecord order = orderOpt.get();
            order.setStatus("PREPARING"); // Cambiamos el estado
            orderRepository.save(order);
            return ResponseEntity.ok("Orden #" + id + " enviada a cocina.");
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // Endpoint para validar transferencia (Pasa de PENDING_VALIDATION a PENDING)
    @PutMapping("/{id}/validate-payment")
    public ResponseEntity<String> validatePayment(@PathVariable Long id) {
        Optional<OrderRecord> orderOpt = orderRepository.findById(id);

        if (orderOpt.isPresent()) {
            OrderRecord order = orderOpt.get();
            order.setStatus("PENDING"); // Pasa a la cola normal de impresión
            orderRepository.save(order);

            // Opcional: Aquí puedes agregar la lógica de Twilio/Meta para mandarle un WhatsApp
            // al cliente diciendo "Tu pago fue validado, comenzamos a preparar tu orden".

            return ResponseEntity.ok("Pago validado para la orden #" + id);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
