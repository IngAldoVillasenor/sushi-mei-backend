package com.sushimei.sushimei.backend.conversation;

import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationManagerTest {

    private static final String PHONE_NUMBER = "525512345678";
    private static final String TEXT_MESSAGE = "Quiero un rollo";
    private static final String RECEIPT_PATH = "receipts/payment.jpg";
    private static final String IMAGE_RECEIPT_INSTRUCTION =
            "EL CLIENTE ACABA DE ENVIAR UNA IMAGEN. Asume que es el comprobante de transferencia. "
                    + "Agradécele por el envío e indícale que el pago está siendo validado por administración y su orden en proceso.";
    private static final String AUDIO_RESPONSE =
            "¡Hola! 😅 Por el momento soy un asistente virtual y no puedo escuchar notas de voz. "
                    + "¿Podrías escribirme tu pedido o enviarme una foto de tu comprobante por aquí?";
    private static final String UNSUPPORTED_MESSAGE_RESPONSE =
            "¡Hola! Por ahora solo puedo procesar mensajes de texto y fotografías de comprobantes. "
                    + "¿Me ayudas escribiendo tu mensaje? 🍣";

    @Mock
    private AiConversationService aiConversationService;

    @Mock
    private ConversationSessionService conversationSessionService;

    @Mock
    private OrderRepository orderRepository;

    private ConversationManager conversationManager;

    @BeforeEach
    void setUp() {
        conversationManager = new ConversationManager(aiConversationService, conversationSessionService, orderRepository);
    }

    @Test
    void recordInboundMessageDelegatesActivityExactlyOnce() {
        conversationManager.recordInboundMessage(PHONE_NUMBER);

        verify(conversationSessionService).recordInboundActivity(PHONE_NUMBER);
        verify(conversationSessionService, never()).recordTransferReceipt(PHONE_NUMBER, RECEIPT_PATH);
        verify(conversationSessionService, never()).resetSession(PHONE_NUMBER);
        verifyNoInteractions(aiConversationService, orderRepository);
    }

    @Test
    void shadowActivityFailureDoesNotPreventTheTextAgentResponse() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(conversationSessionService).recordInboundActivity(PHONE_NUMBER);
        when(aiConversationService.chat(PHONE_NUMBER, PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("agent response");

        conversationManager.recordInboundMessage(PHONE_NUMBER);
        String response = conversationManager.handleTextMessage(PHONE_NUMBER, TEXT_MESSAGE);

        assertThat(response).isEqualTo("agent response");
        verify(conversationSessionService).recordInboundActivity(PHONE_NUMBER);
        verify(conversationSessionService, never()).resetSession(PHONE_NUMBER);
        verify(aiConversationService).chat(PHONE_NUMBER, PHONE_NUMBER, TEXT_MESSAGE);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void textHandlingPreservesAgentArgumentsAndResponseWithoutAdditionalShadowWrites() {
        when(aiConversationService.chat(PHONE_NUMBER, PHONE_NUMBER, TEXT_MESSAGE)).thenReturn("agent response");

        String response = conversationManager.handleTextMessage(PHONE_NUMBER, TEXT_MESSAGE);

        assertThat(response).isEqualTo("agent response");
        verify(aiConversationService).chat(PHONE_NUMBER, PHONE_NUMBER, TEXT_MESSAGE);
        verifyNoInteractions(conversationSessionService, orderRepository);
    }

    @Test
    void imageHandlingRecordsReceiptAssociatesPendingOrderAndPreservesAgentInstruction() {
        OrderRecord pendingOrder = new OrderRecord();
        pendingOrder.setId(42L);
        when(orderRepository.findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(PHONE_NUMBER, "PENDING_VALIDATION"))
                .thenReturn(pendingOrder);
        when(aiConversationService.chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION)).thenReturn("thanks");

        String response = conversationManager.handleImageMessage(PHONE_NUMBER, RECEIPT_PATH);

        assertThat(response).isEqualTo("thanks");
        assertThat(pendingOrder.getTransferReceiptPath()).isEqualTo(RECEIPT_PATH);
        verify(conversationSessionService).recordTransferReceipt(PHONE_NUMBER, RECEIPT_PATH);
        verify(conversationSessionService, never()).recordInboundActivity(PHONE_NUMBER);
        verify(conversationSessionService, never()).resetSession(PHONE_NUMBER);
        verify(orderRepository).findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(PHONE_NUMBER, "PENDING_VALIDATION");
        verify(orderRepository).save(pendingOrder);
        verify(aiConversationService).chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION);
    }

    @Test
    void imageHandlingWithoutPendingOrderContinuesToTheAgent() {
        when(orderRepository.findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(PHONE_NUMBER, "PENDING_VALIDATION"))
                .thenReturn(null);
        when(aiConversationService.chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION)).thenReturn("thanks");

        String response = conversationManager.handleImageMessage(PHONE_NUMBER, RECEIPT_PATH);

        assertThat(response).isEqualTo("thanks");
        verify(conversationSessionService).recordTransferReceipt(PHONE_NUMBER, RECEIPT_PATH);
        verify(conversationSessionService, never()).recordInboundActivity(PHONE_NUMBER);
        verify(conversationSessionService, never()).resetSession(PHONE_NUMBER);
        verify(orderRepository).findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(PHONE_NUMBER, "PENDING_VALIDATION");
        verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.any(OrderRecord.class));
        verify(aiConversationService).chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION);
    }

    @Test
    void nullImagePathSkipsReceiptPersistenceAndOrderRepositoryButStillInvokesTheAgent() {
        when(aiConversationService.chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION)).thenReturn("thanks");

        String response = conversationManager.handleImageMessage(PHONE_NUMBER, null);

        assertThat(response).isEqualTo("thanks");
        verifyNoInteractions(conversationSessionService, orderRepository);
        verify(aiConversationService).chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION);
    }

    @Test
    void shadowReceiptFailureDoesNotBlockOrderAssociationOrTheAgentResponse() {
        OrderRecord pendingOrder = new OrderRecord();
        pendingOrder.setId(42L);
        doThrow(new IllegalStateException("database unavailable"))
                .when(conversationSessionService).recordTransferReceipt(PHONE_NUMBER, RECEIPT_PATH);
        when(orderRepository.findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(PHONE_NUMBER, "PENDING_VALIDATION"))
                .thenReturn(pendingOrder);
        when(aiConversationService.chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION)).thenReturn("thanks");

        String response = conversationManager.handleImageMessage(PHONE_NUMBER, RECEIPT_PATH);

        assertThat(response).isEqualTo("thanks");
        assertThat(pendingOrder.getTransferReceiptPath()).isEqualTo(RECEIPT_PATH);
        verify(conversationSessionService).recordTransferReceipt(PHONE_NUMBER, RECEIPT_PATH);
        verify(conversationSessionService, never()).recordInboundActivity(PHONE_NUMBER);
        verify(conversationSessionService, never()).resetSession(PHONE_NUMBER);
        verify(orderRepository).save(pendingOrder);
        verify(aiConversationService).chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION);
    }

    @Test
    void orderRepositoryFailureIsNotSwallowed() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(orderRepository.findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(PHONE_NUMBER, "PENDING_VALIDATION"))
                .thenThrow(failure);

        assertThatThrownBy(() -> conversationManager.handleImageMessage(PHONE_NUMBER, RECEIPT_PATH))
                .isSameAs(failure);

        verify(conversationSessionService).recordTransferReceipt(PHONE_NUMBER, RECEIPT_PATH);
        verify(conversationSessionService, never()).recordInboundActivity(PHONE_NUMBER);
        verify(conversationSessionService, never()).resetSession(PHONE_NUMBER);
        verify(aiConversationService, never()).chat(PHONE_NUMBER, PHONE_NUMBER, IMAGE_RECEIPT_INSTRUCTION);
    }

    @Test
    void audioResponseIsByteForByteUnchangedAndDoesNotUseTheAgentOrSession() {
        assertThat(conversationManager.handleAudioMessage(PHONE_NUMBER)).isEqualTo(AUDIO_RESPONSE);

        verifyNoInteractions(aiConversationService, conversationSessionService, orderRepository);
    }

    @Test
    void unsupportedResponseIsByteForByteUnchangedAndDoesNotUseTheAgentOrSession() {
        assertThat(conversationManager.handleUnsupportedMessage(PHONE_NUMBER, "video"))
                .isEqualTo(UNSUPPORTED_MESSAGE_RESPONSE);

        verifyNoInteractions(aiConversationService, conversationSessionService, orderRepository);
    }
}
