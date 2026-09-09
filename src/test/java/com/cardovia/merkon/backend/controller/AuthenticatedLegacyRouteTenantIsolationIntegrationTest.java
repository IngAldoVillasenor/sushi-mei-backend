package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.agent.AiConversationService;
import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import com.cardovia.merkon.backend.conversation.ConversationManager;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderRecord;
import com.cardovia.merkon.backend.entity.OrderSource;
import com.cardovia.merkon.backend.order.OrderLifecycleStatus;
import com.cardovia.merkon.backend.repository.OrderRepository;
import com.cardovia.merkon.backend.security.ApplicationRole;
import com.cardovia.merkon.backend.security.SecurityTestKeyConfiguration;
import com.cardovia.merkon.backend.security.TestBusinessAuthentication;
import com.cardovia.merkon.backend.service.CartService;
import com.cardovia.merkon.backend.service.WhatsAppService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies retained authenticated Sushi Mei routes reject a JWT from another business before legacy work starts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({SecurityTestKeyConfiguration.class,
        AuthenticatedLegacyRouteTenantIsolationIntegrationTest.TestInfrastructureConfiguration.class})
class AuthenticatedLegacyRouteTenantIsolationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BusinessRepository businesses;
    @Autowired private LegacyBusinessResolver legacyBusinessResolver;
    @Autowired private OrderRepository orders;
    @Autowired private Clock clock;
    @Autowired private ConversationManager conversationManager;
    @Autowired private CartService cartService;
    @Autowired private AiConversationService aiConversationService;
    @Autowired private WhatsAppService whatsAppService;

    private Business nonLegacyBusiness;

    @BeforeEach
    void setUp() {
        nonLegacyBusiness = businesses.saveAndFlush(Business.create("Business B " + UUID.randomUUID(), clock.instant()));
        clearInvocations(conversationManager, cartService, aiConversationService, whatsAppService);
    }

    @Test
    void nonLegacyBusinessCannotRejectExistingOrUnknownLegacyOrderBeforeAnyLegacySideEffect() throws Exception {
        OrderRecord legacyOrder = legacyRejectableOrder("525511100001");

        mockMvc.perform(post("/api/orders/{id}/reject", legacyOrder.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Sin existencia\"}")
                        .with(TestBusinessAuthentication.authenticated(jdbcTemplate, nonLegacyBusiness.getId(), ApplicationRole.KITCHEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_BUSINESS_FORBIDDEN"));
        mockMvc.perform(post("/api/orders/{id}/reject", 9_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Sin existencia\"}")
                        .with(TestBusinessAuthentication.authenticated(jdbcTemplate, nonLegacyBusiness.getId(), ApplicationRole.KITCHEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_BUSINESS_FORBIDDEN"));

        assertThat(orders.findByIdAndBusinessId(legacyOrder.getId(), legacyBusinessResolver.requireLegacyBusinessId()))
                .get()
                .extracting(OrderRecord::getStatus)
                .isEqualTo(OrderLifecycleStatus.PENDING.persistedValue());
        verifyNoInteractions(cartService, aiConversationService, whatsAppService);
    }

    @Test
    void legacyBusinessCanStillUseTheRetainedRejectRoute() throws Exception {
        OrderRecord legacyOrder = legacyRejectableOrder("525511100002");
        when(aiConversationService.chat(anyString(), anyString(), anyString())).thenReturn("Notificación preparada");
        when(whatsAppService.sendMessage(legacyOrder.getPhoneNumber(), "Notificación preparada")).thenReturn(true);

        mockMvc.perform(post("/api/orders/{id}/reject", legacyOrder.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Sin existencia\"}")
                        .with(TestBusinessAuthentication.authenticated(
                                jdbcTemplate, legacyBusinessResolver, ApplicationRole.KITCHEN)))
                .andExpect(status().isOk());

        assertThat(orders.findByIdAndBusinessId(legacyOrder.getId(), legacyBusinessResolver.requireLegacyBusinessId()))
                .get()
                .extracting(OrderRecord::getStatus)
                .isEqualTo(OrderLifecycleStatus.CANCELLED_CLARIFICATION.persistedValue());
        verify(cartService).reopenCart(legacyOrder.getPhoneNumber());
        verify(aiConversationService).chat(eq(legacyOrder.getPhoneNumber()), eq(legacyOrder.getPhoneNumber()),
                org.mockito.ArgumentMatchers.contains("Sin existencia"));
        verify(whatsAppService).sendMessage(legacyOrder.getPhoneNumber(), "Notificación preparada");
    }

    @Test
    void nonLegacyBusinessCannotEnterLegacyChatBeforeConversationProcessing() throws Exception {
        String phone = "525511100003";

        mockMvc.perform(get("/api/sushi/chat")
                        .queryParam("telefono", phone)
                        .queryParam("mensaje", "Hola")
                        .with(TestBusinessAuthentication.authenticated(
                                jdbcTemplate, nonLegacyBusiness.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_BUSINESS_FORBIDDEN"));

        verifyNoInteractions(conversationManager, cartService, aiConversationService, whatsAppService);
        assertThat(orders.findFirstByBusinessIdAndPhoneNumberAndStatusOrderByCreatedAtDesc(
                legacyBusinessResolver.requireLegacyBusinessId(), phone, OrderLifecycleStatus.PENDING.persistedValue()))
                .isNull();
    }

    @Test
    void legacyBusinessCanStillReachTheRetainedChatRoute() throws Exception {
        when(conversationManager.handleTextMessage("525511100004", "Hola")).thenReturn("Respuesta heredada");

        mockMvc.perform(get("/api/sushi/chat")
                        .queryParam("telefono", "525511100004")
                        .queryParam("mensaje", "Hola")
                        .with(TestBusinessAuthentication.authenticated(
                                jdbcTemplate, legacyBusinessResolver, ApplicationRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(content().string("Respuesta heredada"));

        verify(conversationManager).handleTextMessage("525511100004", "Hola");
    }

    @Test
    void metaWebhookVerificationAndInboundAcknowledgementRemainAnonymous() throws Exception {
        mockMvc.perform(get("/api/whatsapp/webhook")
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", "test-verify-token")
                        .queryParam("hub.challenge", "challenge"))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge"));
        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entry\":[{\"changes\":[{\"value\":{\"statuses\":[]}}]}]}"))
                .andExpect(status().isOk())
                .andExpect(content().string("EVENT_RECEIVED"));
    }

    private OrderRecord legacyRejectableOrder(String phoneNumber) {
        OrderRecord order = new OrderRecord();
        order.setBusiness(legacyBusinessResolver.requireLegacyBusiness());
        order.setPhoneNumber(phoneNumber);
        order.setPaymentMethod(OrderPaymentMethod.CASH);
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setOrderSource(OrderSource.WHATSAPP_AI);
        order.setStatus(OrderLifecycleStatus.PENDING.persistedValue());
        order.setCreatedAt(LocalDateTime.now(clock));
        order.setOrderDetails("Legacy test order");
        return orders.saveAndFlush(order);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean @Primary ConversationManager conversationManager() { return mock(ConversationManager.class); }
        @Bean @Primary CartService cartService() { return mock(CartService.class); }
        @Bean @Primary AiConversationService aiConversationService() { return mock(AiConversationService.class); }
        @Bean @Primary WhatsAppService whatsAppService() { return mock(WhatsAppService.class); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
