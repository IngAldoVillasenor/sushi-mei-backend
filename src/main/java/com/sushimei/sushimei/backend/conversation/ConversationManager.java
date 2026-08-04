package com.sushimei.sushimei.backend.conversation;

import com.sushimei.sushimei.backend.agent.SushiAgent;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates the existing conversational behavior with shadow conversation persistence.
 * It intentionally does not infer or transition checkout state.
 */
@Service
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);

    private static final String IMAGE_RECEIPT_INSTRUCTION =
            "EL CLIENTE ACABA DE ENVIAR UNA IMAGEN. Asume que es el comprobante de transferencia. "
                    + "Agradécele por el envío e indícale que el pago está siendo validado por administración y su orden en proceso.";

    private static final String AUDIO_RESPONSE =
            "¡Hola! 😅 Por el momento soy un asistente virtual y no puedo escuchar notas de voz. "
                    + "¿Podrías escribirme tu pedido o enviarme una foto de tu comprobante por aquí?";

    private static final String UNSUPPORTED_MESSAGE_RESPONSE =
            "¡Hola! Por ahora solo puedo procesar mensajes de texto y fotografías de comprobantes. "
                    + "¿Me ayudas escribiendo tu mensaje? 🍣";

    private final SushiAgent sushiAgent;
    private final ConversationSessionService conversationSessionService;
    private final OrderRepository orderRepository;

    public ConversationManager(SushiAgent sushiAgent,
                               ConversationSessionService conversationSessionService,
                               OrderRepository orderRepository) {
        this.sushiAgent = sushiAgent;
        this.conversationSessionService = conversationSessionService;
        this.orderRepository = orderRepository;
    }

    /**
     * Records an inbound message in shadow mode. The controller calls this exactly once
     * after phone normalization and before dispatching to a message-specific handler.
     */
    public void recordInboundMessage(String phoneNumber) {
        try {
            conversationSessionService.recordInboundActivity(phoneNumber);
        } catch (Exception e) {
            log.warn("Unable to record shadow conversation activity for {}", phoneNumber, e);
        }
    }

    public String handleTextMessage(String phoneNumber, String text) {
        return sushiAgent.chat(phoneNumber, phoneNumber, text);
    }

    public String handleImageMessage(String phoneNumber, String savedReceiptPath) {
        if (savedReceiptPath != null) {
            recordTransferReceiptInShadowMode(phoneNumber, savedReceiptPath);
            associateReceiptWithPendingOrder(phoneNumber, savedReceiptPath);
        }

        return sushiAgent.chat(phoneNumber, phoneNumber, IMAGE_RECEIPT_INSTRUCTION);
    }

    public String handleAudioMessage(String phoneNumber) {
        return AUDIO_RESPONSE;
    }

    public String handleUnsupportedMessage(String phoneNumber, String messageType) {
        log.info("Unsupported WhatsApp message type {} from {}", messageType, phoneNumber);
        return UNSUPPORTED_MESSAGE_RESPONSE;
    }

    private void recordTransferReceiptInShadowMode(String phoneNumber, String receiptPath) {
        try {
            conversationSessionService.recordTransferReceipt(phoneNumber, receiptPath);
        } catch (Exception e) {
            log.warn("Unable to record shadow transfer receipt for {}", phoneNumber, e);
        }
    }

    private void associateReceiptWithPendingOrder(String phoneNumber, String receiptPath) {
        OrderRecord pendingOrder = orderRepository
                .findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(phoneNumber, "PENDING_VALIDATION");

        if (pendingOrder != null) {
            pendingOrder.setTransferReceiptPath(receiptPath);
            orderRepository.save(pendingOrder);
            log.info("Receipt linked to order {}", pendingOrder.getId());
        } else {
            log.warn("Receipt saved but no pending order was found for {}", phoneNumber);
        }
    }
}
