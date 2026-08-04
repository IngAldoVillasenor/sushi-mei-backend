package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.agent.SushiAgent;
import com.sushimei.sushimei.backend.configuration.WhatsAppProperties;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final SushiAgent sushiAgent;
    private final WhatsAppService whatsAppService;
    private final OrderRepository orderRepository;
    private final WhatsAppProperties whatsAppProperties;
    private final ObjectMapper objectMapper;

    public WhatsAppWebhookController(SushiAgent sushiAgent,
                                     WhatsAppService whatsAppService,
                                     OrderRepository orderRepository,
                                     WhatsAppProperties whatsAppProperties,
                                     ObjectMapper objectMapper) {
        this.sushiAgent = sushiAgent;
        this.whatsAppService = whatsAppService;
        this.orderRepository = orderRepository;
        this.whatsAppProperties = whatsAppProperties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/webhook")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        if ("subscribe".equals(mode) && whatsAppProperties.verifyToken().equals(token)) {
            log.info("WhatsApp webhook verified by Meta");
            return ResponseEntity.ok(challenge);
        }

        log.warn("WhatsApp webhook verification failed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token inválido");
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> receiveMessage(@RequestBody String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            JsonNode entry = payload.path("entry").get(0);
            JsonNode changes = entry.path("changes").get(0);
            JsonNode value = changes.path("value");

            if (value.has("messages")) {
                JsonNode messageNode = value.path("messages").get(0);
                String fromPhone = messageNode.path("from").asText();

                if (fromPhone.startsWith("521") && fromPhone.length() == 13) {
                    fromPhone = "52" + fromPhone.substring(3);
                }

                String messageType = messageNode.path("type").asText();
                String aiResponse = "";

                if (messageType.equals("text")) {
                    String textMessage = messageNode.path("text").path("body").asText();
                    log.info("WhatsApp text message received from {}", fromPhone);
                    aiResponse = sushiAgent.chat(fromPhone, fromPhone, textMessage);
                } else if (messageType.equals("image")) {
                    String mediaId = messageNode.path("image").path("id").asText();
                    log.info("WhatsApp image received from {} with media ID {}", fromPhone, mediaId);

                    String savedPath = whatsAppService.downloadWhatsAppImage(mediaId);
                    if (savedPath != null) {
                        OrderRecord pendingOrder = orderRepository.findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(fromPhone, "PENDING_VALIDATION");

                        if (pendingOrder != null) {
                            pendingOrder.setTransferReceiptPath(savedPath);
                            orderRepository.save(pendingOrder);
                            log.info("Receipt linked to order {}", pendingOrder.getId());
                        } else {
                            log.warn("Receipt saved but no pending order was found for {}", fromPhone);
                        }
                    }

                    String promptParaIA = "EL CLIENTE ACABA DE ENVIAR UNA IMAGEN. Asume que es el comprobante de transferencia. Agradécele por el envío e indícale que el pago está siendo validado por administración y su orden en proceso.";
                    aiResponse = sushiAgent.chat(fromPhone, fromPhone, promptParaIA);
                } else if (messageType.equals("audio")) {
                    log.info("WhatsApp audio received from {}; responding without the agent", fromPhone);
                    aiResponse = "¡Hola! 😅 Por el momento soy un asistente virtual y no puedo escuchar notas de voz. ¿Podrías escribirme tu pedido o enviarme una foto de tu comprobante por aquí?";
                } else {
                    log.info("Unsupported WhatsApp message type {} from {}", messageType, fromPhone);
                    aiResponse = "¡Hola! Por ahora solo puedo procesar mensajes de texto y fotografías de comprobantes. ¿Me ayudas escribiendo tu mensaje? 🍣";
                }

                log.info("WhatsApp response prepared for {}", fromPhone);
                whatsAppService.sendMessage(fromPhone, aiResponse);
            }

            return ResponseEntity.ok("EVENT_RECEIVED");
        } catch (Exception e) {
            log.warn("Unable to process WhatsApp webhook payload", e);
            return ResponseEntity.ok("ERROR_CONTROLADO");
        }
    }
}
