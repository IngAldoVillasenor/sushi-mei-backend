package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.configuration.WhatsAppProperties;
import com.sushimei.sushimei.backend.conversation.ConversationManager;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import com.sushimei.sushimei.backend.whatsapp.InboundMessageClaimOutcome;
import com.sushimei.sushimei.backend.whatsapp.InboundMessageFailureStage;
import com.sushimei.sushimei.backend.whatsapp.InboundMessageIdempotencyService;
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
    private static final String PROCESSING_FAILURE_RESPONSE =
            "Tuve un problema al procesar tu mensaje. Para evitar duplicados, escribe \"¿Qué llevo?\" "
                    + "antes de continuar o vuelve a intentarlo.";

    private final ConversationManager conversationManager;
    private final WhatsAppService whatsAppService;
    private final WhatsAppProperties whatsAppProperties;
    private final ObjectMapper objectMapper;
    private final InboundMessageIdempotencyService inboundMessageIdempotencyService;

    public WhatsAppWebhookController(ConversationManager conversationManager,
                                     WhatsAppService whatsAppService,
                                     WhatsAppProperties whatsAppProperties,
                                     ObjectMapper objectMapper,
                                     InboundMessageIdempotencyService inboundMessageIdempotencyService) {
        this.conversationManager = conversationManager;
        this.whatsAppService = whatsAppService;
        this.whatsAppProperties = whatsAppProperties;
        this.objectMapper = objectMapper;
        this.inboundMessageIdempotencyService = inboundMessageIdempotencyService;
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
            JsonNode messages = value.path("messages");

            if (messages.isArray() && !messages.isEmpty()) {
                JsonNode messageNode = messages.get(0);
                String messageId = messageNode.path("id").asText();

                if (messageId == null || messageId.isBlank()) {
                    log.warn("WhatsApp inbound event has no usable message ID; skipping operational processing");
                    return ResponseEntity.ok("EVENT_RECEIVED");
                }

                String fromPhone = messageNode.path("from").asText();
                if (fromPhone.startsWith("521") && fromPhone.length() == 13) {
                    fromPhone = "52" + fromPhone.substring(3);
                }

                String messageType = messageNode.path("type").asText();
                InboundMessageClaimOutcome claimOutcome = inboundMessageIdempotencyService
                        .claim(messageId, fromPhone, messageType);

                if (claimOutcome == InboundMessageClaimOutcome.DUPLICATE) {
                    log.info("WhatsApp inbound outcome=DUPLICATE messageId={} phone={} messageType={}",
                            messageId, fromPhone, messageType);
                    return ResponseEntity.ok("EVENT_RECEIVED");
                }

                log.info("WhatsApp inbound outcome=NEW messageId={} phone={} messageType={}",
                        messageId, fromPhone, messageType);
                InboundMessageFailureStage failureStage = InboundMessageFailureStage.RECORD_INBOUND;
                try {
                    conversationManager.recordInboundMessage(fromPhone);
                    failureStage = InboundMessageFailureStage.HANDLE_MESSAGE;
                    String aiResponse = handleMessage(messageNode, fromPhone, messageType);

                    log.info("WhatsApp response prepared for {}", fromPhone);
                    failureStage = InboundMessageFailureStage.SEND_RESPONSE;
                    if (!whatsAppService.sendMessage(fromPhone, aiResponse)) {
                        throw new IllegalStateException("WhatsApp response delivery failed");
                    }

                    failureStage = InboundMessageFailureStage.MARK_COMPLETED;
                    inboundMessageIdempotencyService.markCompleted(messageId);
                    log.info("WhatsApp inbound outcome=COMPLETED messageId={} phone={} messageType={}",
                            messageId, fromPhone, messageType);
                } catch (Exception e) {
                    markInboundMessageFailed(messageId, fromPhone, messageType, failureStage, e);
                    sendProcessingFailureResponse(fromPhone, failureStage);
                    return ResponseEntity.ok("ERROR_CONTROLADO");
                }
            }

            return ResponseEntity.ok("EVENT_RECEIVED");
        } catch (Exception e) {
            log.warn("Unable to process WhatsApp webhook payload: {}", e.getClass().getSimpleName());
            return ResponseEntity.ok("ERROR_CONTROLADO");
        }
    }

    private String handleMessage(JsonNode messageNode, String phoneNumber, String messageType) {
        if (messageType.equals("text")) {
            String textMessage = messageNode.path("text").path("body").asText();
            log.info("WhatsApp text message received from {}", phoneNumber);
            return conversationManager.handleTextMessage(phoneNumber, textMessage);
        }
        if (messageType.equals("image")) {
            String mediaId = messageNode.path("image").path("id").asText();
            log.info("WhatsApp image received from {}", phoneNumber);
            String savedPath = whatsAppService.downloadWhatsAppImage(mediaId);
            return conversationManager.handleImageMessage(phoneNumber, savedPath);
        }
        if (messageType.equals("audio")) {
            log.info("WhatsApp audio received from {}; responding without the agent", phoneNumber);
            return conversationManager.handleAudioMessage(phoneNumber);
        }
        return conversationManager.handleUnsupportedMessage(phoneNumber, messageType);
    }

    private void markInboundMessageFailed(String messageId,
                                          String phoneNumber,
                                          String messageType,
                                          InboundMessageFailureStage failureStage,
                                          Exception failure) {
        try {
            inboundMessageIdempotencyService.markFailed(messageId, failureStage, failure);
        } catch (Exception statusFailure) {
            log.warn("WhatsApp inbound outcome=FAILED messageId={} phone={} messageType={} stage={} "
                            + "failure={} statusUpdateFailure={}",
                    messageId, phoneNumber, messageType, failureStage, failure.getClass().getSimpleName(),
                    statusFailure.getClass().getSimpleName());
            return;
        }

        log.warn("WhatsApp inbound outcome=FAILED messageId={} phone={} messageType={} stage={} failure={}",
                messageId, phoneNumber, messageType, failureStage, failure.getClass().getSimpleName());
    }

    private void sendProcessingFailureResponse(String phoneNumber, InboundMessageFailureStage failureStage) {
        if (failureStage != InboundMessageFailureStage.RECORD_INBOUND
                && failureStage != InboundMessageFailureStage.HANDLE_MESSAGE) {
            return;
        }
        boolean delivered = whatsAppService.sendMessage(phoneNumber, PROCESSING_FAILURE_RESPONSE);
        log.info("WhatsApp recovery response outcome={} phone={} failedStage={}",
                delivered ? "SENT" : "FAILED", phoneNumber, failureStage);
    }
}
