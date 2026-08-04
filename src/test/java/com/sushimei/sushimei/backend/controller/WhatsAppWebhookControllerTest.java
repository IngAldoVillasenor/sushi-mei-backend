package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.agent.SushiAgent;
import com.sushimei.sushimei.backend.configuration.WhatsAppProperties;
import com.sushimei.sushimei.backend.conversation.ConversationSessionService;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private static final String RAW_PHONE_NUMBER = "5215512345678";
    private static final String NORMALIZED_PHONE_NUMBER = "525512345678";
    private static final String TEXT_MESSAGE = "Quiero un rollo";

    @Mock
    private SushiAgent sushiAgent;

    @Mock
    private WhatsAppService whatsAppService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ConversationSessionService conversationSessionService;

    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WhatsAppWebhookController(
                sushiAgent,
                whatsAppService,
                orderRepository,
                new WhatsAppProperties("test-token", "test-phone", "test-verify", "v19.0"),
                new ObjectMapper(),
                conversationSessionService);
    }

    @Test
    void inboundTextRecordsActivityAndKeepsExistingAgentInvocationArguments() {
        when(sushiAgent.chat(NORMALIZED_PHONE_NUMBER, NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");

        ResponseEntity<String> response = controller.receiveMessage(textPayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        verify(conversationSessionService).recordInboundActivity(NORMALIZED_PHONE_NUMBER);
        verify(conversationSessionService, never()).recordTransferReceipt(anyString(), anyString());
        verify(sushiAgent).chat(NORMALIZED_PHONE_NUMBER, NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE);
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "response");
    }

    @Test
    void downloadedImageRecordsReceiptWithoutChangingExistingOrderOrAgentFlow() {
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn("receipts/media-123.jpg");
        when(sushiAgent.chat(eq(NORMALIZED_PHONE_NUMBER), eq(NORMALIZED_PHONE_NUMBER), anyString())).thenReturn("thanks");

        ResponseEntity<String> response = controller.receiveMessage(imagePayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        verify(conversationSessionService).recordInboundActivity(NORMALIZED_PHONE_NUMBER);
        verify(conversationSessionService).recordTransferReceipt(NORMALIZED_PHONE_NUMBER, "receipts/media-123.jpg");
        verify(orderRepository).findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(
                NORMALIZED_PHONE_NUMBER, "PENDING_VALIDATION");
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
    }

    @Test
    void shadowPersistenceFailureDoesNotPreventExistingTextResponse() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(conversationSessionService).recordInboundActivity(NORMALIZED_PHONE_NUMBER);
        when(sushiAgent.chat(NORMALIZED_PHONE_NUMBER, NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("response");

        ResponseEntity<String> response = controller.receiveMessage(textPayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        verify(sushiAgent).chat(NORMALIZED_PHONE_NUMBER, NORMALIZED_PHONE_NUMBER, TEXT_MESSAGE);
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "response");
    }

    @Test
    void failedImageDownloadDoesNotRecordTransferReceipt() {
        when(whatsAppService.downloadWhatsAppImage("media-123")).thenReturn(null);
        when(sushiAgent.chat(eq(NORMALIZED_PHONE_NUMBER), eq(NORMALIZED_PHONE_NUMBER), anyString())).thenReturn("thanks");

        ResponseEntity<String> response = controller.receiveMessage(imagePayload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("EVENT_RECEIVED");
        verify(conversationSessionService).recordInboundActivity(NORMALIZED_PHONE_NUMBER);
        verify(conversationSessionService, never()).recordTransferReceipt(anyString(), anyString());
        verify(whatsAppService).sendMessage(NORMALIZED_PHONE_NUMBER, "thanks");
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
}
