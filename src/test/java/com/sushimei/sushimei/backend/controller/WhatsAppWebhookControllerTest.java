package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.configuration.WhatsAppProperties;
import com.sushimei.sushimei.backend.conversation.ConversationManager;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private static final String RAW_PHONE_NUMBER = "5215512345678";
    private static final String NORMALIZED_PHONE_NUMBER = "525512345678";
    private static final String TEXT_MESSAGE = "Quiero un rollo";

    @Mock
    private ConversationManager conversationManager;

    @Mock
    private WhatsAppService whatsAppService;

    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WhatsAppWebhookController(
                conversationManager,
                whatsAppService,
                new WhatsAppProperties("test-token", "test-phone", "test-verify", "v19.0"),
                new ObjectMapper());
    }

    @Test
    void inboundTextNormalizesPhoneRecordsActivityOnceDelegatesAndForwardsTheResponse() {
        when(conversationManager.handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");

        ResponseEntity<String> response = controller.receiveMessage(textPayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        InOrder inOrder = inOrder(conversationManager, whatsAppService);
        inOrder.verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        inOrder.verify(conversationManager).handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE);
        inOrder.verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "response");
    }

    @Test
    void downloadedImageDelegatesTheSavedPathAfterActivityAndForwardsTheManagerResponse() {
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn("receipts/media-123.jpg");
        when(conversationManager.handleImageMessage(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg"))
                .thenReturn("thanks");

        ResponseEntity<String> response = controller.receiveMessage(imagePayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        InOrder inOrder = inOrder(conversationManager, whatsAppService);
        inOrder.verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        inOrder.verify(whatsAppService).downloadWhatsAppImage("media-123");
        inOrder.verify(conversationManager).handleImageMessage(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg");
        inOrder.verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
    }

    @Test
    void failedImageDownloadDelegatesANullPathAndForwardsTheManagerResponse() {
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn(null);
        when(conversationManager.handleImageMessage(NORMALIZED_PHONE_NUMBER, null)).thenReturn("thanks");

        ResponseEntity<String> response = controller.receiveMessage(imagePayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        InOrder inOrder = inOrder(conversationManager, whatsAppService);
        inOrder.verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        inOrder.verify(whatsAppService).downloadWhatsAppImage("media-123");
        inOrder.verify(conversationManager).handleImageMessage(NORMALIZED_PHONE_NUMBER, null);
        inOrder.verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
    }

    @Test
    void audioDelegatesWithTheNormalizedPhoneAndForwardsTheManagerResponse() {
        when(conversationManager.handleAudioMessage(NORMALIZED_PHONE_NUMBER)).thenReturn("audio response");

        ResponseEntity<String> response = controller.receiveMessage(audioPayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager).handleAudioMessage(NORMALIZED_PHONE_NUMBER);
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "audio response");
    }

    @Test
    void unsupportedMessageDelegatesTheTypeAndForwardsTheManagerResponse() {
        when(conversationManager.handleUnsupportedMessage(NORMALIZED_PHONE_NUMBER, "video"))
                .thenReturn("unsupported response");

        ResponseEntity<String> response = controller.receiveMessage(unsupportedPayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager).handleUnsupportedMessage(NORMALIZED_PHONE_NUMBER, "video");
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "unsupported response");
    }

    private String textPayload() {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(RAW_PHONE_NUMBER, TEXT_MESSAGE);
    }

    private String imagePayload() {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"%s","type":"image","image":{"id":"media-123"}}]}}]}]}
                """.formatted(RAW_PHONE_NUMBER);
    }

    private String audioPayload() {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"%s","type":"audio"}]}}]}]}
                """.formatted(RAW_PHONE_NUMBER);
    }

    private String unsupportedPayload() {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"%s","type":"video"}]}}]}]}
                """.formatted(RAW_PHONE_NUMBER);
    }
}
