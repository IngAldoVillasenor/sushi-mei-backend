package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.configuration.WhatsAppProperties;
import com.sushimei.sushimei.backend.conversation.ConversationManager;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import com.sushimei.sushimei.backend.whatsapp.InboundMessageClaimOutcome;
import com.sushimei.sushimei.backend.whatsapp.InboundMessageIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private static final String RAW_PHONE_NUMBER = "5215512345678";
    private static final String NORMALIZED_PHONE_NUMBER = "525512345678";
    private static final String TEXT_MESSAGE = "Quiero un rollo";
    private static final String TEXT_MESSAGE_ID = "wamid-text-1";
    private static final String IMAGE_MESSAGE_ID = "wamid-image-1";

    @Mock
    private ConversationManager conversationManager;

    @Mock
    private WhatsAppService whatsAppService;

    @Mock
    private InboundMessageIdempotencyService inboundMessageIdempotencyService;

    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WhatsAppWebhookController(
                conversationManager,
                whatsAppService,
                new WhatsAppProperties("test-token", "test-phone", "test-verify", "v19.0"),
                new ObjectMapper(),
                inboundMessageIdempotencyService);
    }

    @Test
    void inboundTextNormalizesPhoneRecordsActivityOnceDelegatesAndForwardsTheResponse() {
        when(inboundMessageIdempotencyService.claim(TEXT_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "text"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(conversationManager.handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "response")).thenReturn(true);

        ResponseEntity<String> response = controller.receiveMessage(textPayload(TEXT_MESSAGE_ID));

        assertEventReceived(response);
        InOrder inOrder = inOrder(inboundMessageIdempotencyService, conversationManager, whatsAppService);
        inOrder.verify(inboundMessageIdempotencyService).claim(TEXT_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "text");
        inOrder.verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        inOrder.verify(conversationManager).handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE);
        inOrder.verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "response");
        inOrder.verify(inboundMessageIdempotencyService).markCompleted(TEXT_MESSAGE_ID);
    }

    @Test
    void duplicateTextMessageIdProcessesAndSendsExactlyOnce() {
        when(inboundMessageIdempotencyService.claim(TEXT_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "text"))
                .thenReturn(InboundMessageClaimOutcome.NEW, InboundMessageClaimOutcome.DUPLICATE);
        when(conversationManager.handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "response")).thenReturn(true);

        assertEventReceived(controller.receiveMessage(textPayload(TEXT_MESSAGE_ID)));
        assertEventReceived(controller.receiveMessage(textPayload(TEXT_MESSAGE_ID)));

        verify(inboundMessageIdempotencyService, times(2)).claim(TEXT_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "text");
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager).handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE);
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "response");
        verify(inboundMessageIdempotencyService).markCompleted(TEXT_MESSAGE_ID);
    }

    @Test
    void duplicateImageMessageIdDownloadsAndSendsExactlyOnce() {
        when(inboundMessageIdempotencyService.claim(IMAGE_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "image"))
                .thenReturn(InboundMessageClaimOutcome.NEW, InboundMessageClaimOutcome.DUPLICATE);
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn("receipts/media-123.jpg");
        when(conversationManager.handleImageMessage(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg"))
                .thenReturn("thanks");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "thanks")).thenReturn(true);

        assertEventReceived(controller.receiveMessage(imagePayload(IMAGE_MESSAGE_ID)));
        assertEventReceived(controller.receiveMessage(imagePayload(IMAGE_MESSAGE_ID)));

        verify(whatsAppService).downloadWhatsAppImage("media-123");
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager).handleImageMessage(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg");
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
        verify(inboundMessageIdempotencyService).markCompleted(IMAGE_MESSAGE_ID);
    }

    @Test
    void differentMessageIdsWithIdenticalTextProcessIndependently() {
        when(inboundMessageIdempotencyService.claim("wamid-text-1", NORMALIZED_PHONE_NUMBER, "text"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(inboundMessageIdempotencyService.claim("wamid-text-2", NORMALIZED_PHONE_NUMBER, "text"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(conversationManager.handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "response")).thenReturn(true);

        assertEventReceived(controller.receiveMessage(textPayload("wamid-text-1")));
        assertEventReceived(controller.receiveMessage(textPayload("wamid-text-2")));

        verify(conversationManager, times(2)).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager, times(2)).handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE);
        verify(whatsAppService, times(2)).sendMessage(NORMALIZED_PHONE_NUMBER, "response");
        verify(inboundMessageIdempotencyService).markCompleted("wamid-text-1");
        verify(inboundMessageIdempotencyService).markCompleted("wamid-text-2");
    }

    @Test
    void downloadedImageDelegatesTheSavedPathAfterActivityAndForwardsTheManagerResponse() {
        when(inboundMessageIdempotencyService.claim(IMAGE_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "image"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn("receipts/media-123.jpg");
        when(conversationManager.handleImageMessage(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg"))
                .thenReturn("thanks");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "thanks")).thenReturn(true);

        ResponseEntity<String> response = controller.receiveMessage(imagePayload(IMAGE_MESSAGE_ID));

        assertEventReceived(response);
        InOrder inOrder = inOrder(inboundMessageIdempotencyService, conversationManager, whatsAppService);
        inOrder.verify(inboundMessageIdempotencyService).claim(IMAGE_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "image");
        inOrder.verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        inOrder.verify(whatsAppService).downloadWhatsAppImage("media-123");
        inOrder.verify(conversationManager).handleImageMessage(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg");
        inOrder.verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
        inOrder.verify(inboundMessageIdempotencyService).markCompleted(IMAGE_MESSAGE_ID);
    }

    @Test
    void failedImageDownloadDelegatesANullPathAndForwardsTheManagerResponse() {
        when(inboundMessageIdempotencyService.claim(IMAGE_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "image"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn(null);
        when(conversationManager.handleImageMessage(NORMALIZED_PHONE_NUMBER, null)).thenReturn("thanks");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "thanks")).thenReturn(true);

        ResponseEntity<String> response = controller.receiveMessage(imagePayload(IMAGE_MESSAGE_ID));

        assertEventReceived(response);
        InOrder inOrder = inOrder(inboundMessageIdempotencyService, conversationManager, whatsAppService);
        inOrder.verify(inboundMessageIdempotencyService).claim(IMAGE_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "image");
        inOrder.verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        inOrder.verify(whatsAppService).downloadWhatsAppImage("media-123");
        inOrder.verify(conversationManager).handleImageMessage(NORMALIZED_PHONE_NUMBER, null);
        inOrder.verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
        inOrder.verify(inboundMessageIdempotencyService).markCompleted(IMAGE_MESSAGE_ID);
    }

    @Test
    void audioDelegatesWithTheNormalizedPhoneAndForwardsTheManagerResponse() {
        when(inboundMessageIdempotencyService.claim("wamid-audio-1", NORMALIZED_PHONE_NUMBER, "audio"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(conversationManager.handleAudioMessage(NORMALIZED_PHONE_NUMBER)).thenReturn("audio response");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "audio response")).thenReturn(true);

        ResponseEntity<String> response = controller.receiveMessage(audioPayload("wamid-audio-1"));

        assertEventReceived(response);
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager).handleAudioMessage(NORMALIZED_PHONE_NUMBER);
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "audio response");
    }

    @Test
    void unsupportedMessageDelegatesTheTypeAndForwardsTheManagerResponse() {
        when(inboundMessageIdempotencyService.claim("wamid-video-1", NORMALIZED_PHONE_NUMBER, "video"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(conversationManager.handleUnsupportedMessage(NORMALIZED_PHONE_NUMBER, "video"))
                .thenReturn("unsupported response");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "unsupported response")).thenReturn(true);

        ResponseEntity<String> response = controller.receiveMessage(unsupportedPayload("wamid-video-1"));

        assertEventReceived(response);
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(conversationManager).handleUnsupportedMessage(NORMALIZED_PHONE_NUMBER, "video");
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "unsupported response");
    }

    @Test
    void statusOnlyWebhookPayloadPerformsNoClaimOrSend() {
        assertEventReceived(controller.receiveMessage("""
                {"entry":[{"changes":[{"value":{"statuses":[{"id":"status-1"}]}}]}]}
                """));

        verifyNoInteractions(inboundMessageIdempotencyService, conversationManager, whatsAppService);
    }

    @Test
    void missingOrBlankMessageIdSkipsOperationalProcessing() {
        assertEventReceived(controller.receiveMessage(textPayload(null)));
        assertEventReceived(controller.receiveMessage(textPayload("   ")));

        verifyNoInteractions(inboundMessageIdempotencyService, conversationManager, whatsAppService);
    }

    @Test
    void managerFailureMarksTheClaimFailedWithoutSendingAResponse() {
        when(inboundMessageIdempotencyService.claim(TEXT_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "text"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(conversationManager.handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE))
                .thenThrow(new IllegalStateException("manager failed"));

        ResponseEntity<String> response = controller.receiveMessage(textPayload(TEXT_MESSAGE_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ERROR_CONTROLADO");
        verify(conversationManager).recordInboundMessage(NORMALIZED_PHONE_NUMBER);
        verify(inboundMessageIdempotencyService).markFailed(TEXT_MESSAGE_ID);
        verify(whatsAppService, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void outboundSendFailureMarksTheClaimFailedAndReturnsControlledAcknowledgement() {
        when(inboundMessageIdempotencyService.claim(TEXT_MESSAGE_ID, NORMALIZED_PHONE_NUMBER, "text"))
                .thenReturn(InboundMessageClaimOutcome.NEW);
        when(conversationManager.handleTextMessage(NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");
        when(whatsAppService.sendMessage(NORMALIZED_PHONE_NUMBER, "response")).thenReturn(false);

        ResponseEntity<String> response = controller.receiveMessage(textPayload(TEXT_MESSAGE_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ERROR_CONTROLADO");
        verify(inboundMessageIdempotencyService).markFailed(TEXT_MESSAGE_ID);
    }

    private void assertEventReceived(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
    }

    private String textPayload(String messageId) {
        String idFragment = messageId == null ? "" : "\"id\":\"%s\",".formatted(messageId);
        return """
                {"entry":[{"changes":[{"value":{"messages":[{%s"from":"%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(idFragment, RAW_PHONE_NUMBER, TEXT_MESSAGE);
    }

    private String imagePayload(String messageId) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"%s","type":"image","image":{"id":"media-123"}}]}}]}]}
                """.formatted(messageId, RAW_PHONE_NUMBER);
    }

    private String audioPayload(String messageId) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"%s","type":"audio"}]}}]}]}
                """.formatted(messageId, RAW_PHONE_NUMBER);
    }

    private String unsupportedPayload(String messageId) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"%s","type":"video"}]}}]}]}
                """.formatted(messageId, RAW_PHONE_NUMBER);
    }
}
