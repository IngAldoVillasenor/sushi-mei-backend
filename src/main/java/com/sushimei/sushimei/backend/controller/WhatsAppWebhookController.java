package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.agent.SushiAgent;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppWebhookController {

    private final String VERIFY_TOKEN = "sushimei_secreto_123";
    private final SushiAgent sushiAgent;
    private final WhatsAppService whatsAppService;
    private final OrderRepository orderRepository; // INYECTAMOS EL REPOSITORIO

    public WhatsAppWebhookController(SushiAgent sushiAgent, WhatsAppService whatsAppService, OrderRepository orderRepository) {
        this.sushiAgent = sushiAgent;
        this.whatsAppService = whatsAppService;
        this.orderRepository = orderRepository;
    }

    @GetMapping("/webhook")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        if ("subscribe".equals(mode) && VERIFY_TOKEN.equals(token)) {
            System.out.println("✅ ¡Webhook verificado exitosamente por Meta!");
            return ResponseEntity.ok(challenge);
        } else {
            System.out.println("❌ Falló la verificación del Webhook.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token inválido");
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> receiveMessage(@RequestBody String rawPayload) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode payload = mapper.readTree(rawPayload);

            JsonNode entry = payload.path("entry").get(0);
            JsonNode changes = entry.path("changes").get(0);
            JsonNode value = changes.path("value");

            if (value.has("messages")) {
                JsonNode messageNode = value.path("messages").get(0);
                String fromPhone = messageNode.path("from").asText();

                // Sanear número para México
                if (fromPhone.startsWith("521") && fromPhone.length() == 13) {
                    fromPhone = "52" + fromPhone.substring(3);
                }

                String messageType = messageNode.path("type").asText();
                String aiResponse = "";

                // --- EVALUACIÓN DE TIPO DE MENSAJE ---
                if (messageType.equals("text")) {

                    String textMessage = messageNode.path("text").path("body").asText();
                    System.out.println("📱 Mensaje de texto [" + fromPhone + "]: " + textMessage);
                    aiResponse = sushiAgent.chat(fromPhone, fromPhone, textMessage);

                } else if (messageType.equals("image")) {

                    String mediaId = messageNode.path("image").path("id").asText();
                    System.out.println("📸 Imagen recibida del número [" + fromPhone + "]. Media ID: " + mediaId);

                    // 1. Descargamos la imagen
                    String savedPath = whatsAppService.downloadWhatsAppImage(mediaId);

                    // 2. BUSCAMOS LA ORDEN PENDIENTE Y LE ASIGNAMOS LA IMAGEN
                    if (savedPath != null) {
                        OrderRecord pendingOrder = orderRepository.findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(fromPhone, "PENDING_VALIDATION");

                        if (pendingOrder != null) {
                            pendingOrder.setTransferReceiptPath(savedPath);
                            orderRepository.save(pendingOrder);
                            System.out.println("💾 Comprobante vinculado exitosamente al Ticket #" + pendingOrder.getId());
                        } else {
                            System.out.println("⚠️ Imagen guardada, pero no se encontró ninguna orden PENDIENTE para vincularla.");
                        }
                    }

                    // 3. Avisamos a la IA
                    String promptParaIA = "EL CLIENTE ACABA DE ENVIAR UNA IMAGEN. Asume que es el comprobante de transferencia. Agradécele por el envío e indícale que el pago está siendo validado por administración y su orden en proceso.";
                    aiResponse = sushiAgent.chat(fromPhone, fromPhone, promptParaIA);

                } else if (messageType.equals("audio")) {

                    System.out.println("🎤 Nota de voz recibida de [" + fromPhone + "]. Respondiendo sin IA.");
                    aiResponse = "¡Hola! 😅 Por el momento soy un asistente virtual y no puedo escuchar notas de voz. ¿Podrías escribirme tu pedido o enviarme una foto de tu comprobante por aquí?";

                } else {

                    System.out.println("📎 Archivo no soportado (" + messageType + ") de [" + fromPhone + "].");
                    aiResponse = "¡Hola! Por ahora solo puedo procesar mensajes de texto y fotografías de comprobantes. ¿Me ayudas escribiendo tu mensaje? 🍣";

                }

                System.out.println("🤖 Respuesta enviada: " + aiResponse);
                whatsAppService.sendMessage(fromPhone, aiResponse);
            }

            return ResponseEntity.ok("EVENT_RECEIVED");

        } catch (Exception e) {
            System.err.println("⚠️ Error procesando el payload de WhatsApp: " + e.getMessage());
            return ResponseEntity.ok("ERROR_CONTROLADO");
        }
    }
}