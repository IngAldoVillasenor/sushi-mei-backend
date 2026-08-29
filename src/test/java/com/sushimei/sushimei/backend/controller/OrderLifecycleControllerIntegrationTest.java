package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.security.AuthResponse;
import com.sushimei.sushimei.backend.security.AuthService;
import com.sushimei.sushimei.backend.security.LoginRequest;
import com.sushimei.sushimei.backend.security.PasswordPolicyService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        OrderLifecycleControllerIntegrationTest.TestInfrastructureConfiguration.class})
class OrderLifecycleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void missingAndInvalidLifecycleCommandsReturnStableErrors() throws Exception {
        OrderRecord pending = order("PENDING", OrderPaymentMethod.CASH, 1);

        mockMvc.perform(put("/api/orders/{id}/prepare", 999999L).with(user("manager").roles("MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(put("/api/orders/{id}/complete", pending.getId()).with(user("manager").roles("MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));
        mockMvc.perform(put("/api/orders/{id}/ready", pending.getId()).with(user("manager").roles("MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));
    }

    @Test
    void activeEndpointReturnsOperationalDtosOldestFirstWithoutEntityInternals() throws Exception {
        OrderRecord older = order("PENDING", OrderPaymentMethod.CASH, 1);
        order("COMPLETED", OrderPaymentMethod.CASH, 2);
        OrderRecord newer = order("PREPARING", OrderPaymentMethod.CASH, 3);
        OrderRecord ready = order("READY", OrderPaymentMethod.CASH, 4);

        mockMvc.perform(get("/api/orders/active").with(user("kitchen").roles("KITCHEN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(older.getId()))
                .andExpect(jsonPath("$[1].id").value(newer.getId()))
                .andExpect(jsonPath("$[2].id").value(ready.getId()))
                .andExpect(jsonPath("$[0].requestFingerprint").doesNotExist())
                .andExpect(jsonPath("$[0].createdByUserId").doesNotExist())
                .andExpect(jsonPath("$[0].orderLines").doesNotExist());
    }

    @Test
    void roleAuthorizationRemainsUnchangedForOperationalCommands() throws Exception {
        OrderRecord cashierForbidden = order("PENDING", OrderPaymentMethod.CASH, 1);
        OrderRecord ownerPending = order("PENDING", OrderPaymentMethod.CASH, 2);
        OrderRecord managerPending = order("PENDING", OrderPaymentMethod.CASH, 3);
        OrderRecord kitchenPending = order("PENDING", OrderPaymentMethod.CASH, 4);
        OrderRecord cashierPreparing = order("PREPARING", OrderPaymentMethod.CASH, 5);
        OrderRecord ownerPreparing = order("PREPARING", OrderPaymentMethod.CASH, 6);
        OrderRecord managerPreparing = order("PREPARING", OrderPaymentMethod.CASH, 7);
        OrderRecord kitchenPreparing = order("PREPARING", OrderPaymentMethod.CASH, 8);
        OrderRecord kitchenForbiddenTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 9);
        OrderRecord ownerTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 10);
        OrderRecord managerTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 11);
        OrderRecord cashierTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 12);

        mockMvc.perform(put("/api/orders/{id}/prepare", cashierForbidden.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/orders/{id}/prepare", cashierForbidden.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("cashier")
                                .roles("CASHIER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/{id}/prepare", ownerPending.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner")
                                .roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/prepare", managerPending.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("manager")
                                .roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/prepare", kitchenPending.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("kitchen")
                                .roles("KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/ready", cashierPreparing.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("cashier")
                                .roles("CASHIER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/{id}/ready", ownerPreparing.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner")
                                .roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/ready", managerPreparing.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("manager")
                                .roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/ready", kitchenPreparing.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("kitchen")
                                .roles("KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", kitchenForbiddenTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("kitchen")
                                .roles("KITCHEN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", ownerTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner")
                                .roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", managerTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("manager")
                                .roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", cashierTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("cashier")
                                .roles("CASHIER")))
                .andExpect(status().isOk());
    }

    @Test
    void physicalPosVoidUsesAuthenticatedActorAndReturnsStructuredAuditEvidence() throws Exception {
        OrderRecord order = order("PREPARING", OrderPaymentMethod.CASH, 1);
        TestActor actor = insertActor("CASHIER");

        mockMvc.perform(put("/api/orders/{id}/void", order.getId())
                        .header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  Cliente canceló el pedido  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.getId()))
                .andExpect(jsonPath("$.previousStatus").value("PREPARING"))
                .andExpect(jsonPath("$.currentStatus").value("VOIDED"))
                .andExpect(jsonPath("$.voidReason").value("Cliente canceló el pedido"))
                .andExpect(jsonPath("$.voidedAt").isNotEmpty())
                .andExpect(jsonPath("$.voidedByUserId").value(actor.id()));

        OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.VOIDED.persistedValue());
        assertThat(persisted.getVoidReason()).isEqualTo("Cliente canceló el pedido");
        assertThat(persisted.getVoidedByUserId()).isEqualTo(actor.id());
    }

    @Test
    void voidEndpointAllowsOwnerManagerAndCashierButRejectsKitchenAndAnonymous() throws Exception {
        OrderRecord ownerOrder = order("PENDING", OrderPaymentMethod.CASH, 1);
        OrderRecord managerOrder = order("PENDING", OrderPaymentMethod.CASH, 2);
        OrderRecord cashierOrder = order("PENDING", OrderPaymentMethod.CASH, 3);
        OrderRecord kitchenOrder = order("PENDING", OrderPaymentMethod.CASH, 4);
        TestActor owner = insertActor("OWNER");
        TestActor manager = insertActor("MANAGER");
        TestActor cashier = insertActor("CASHIER");

        mockMvc.perform(put("/api/orders/{id}/void", ownerOrder.getId())
                        .header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cliente canceló\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/void", managerOrder.getId())
                        .header("Authorization", "Bearer " + manager.accessToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cliente canceló\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/void", cashierOrder.getId())
                        .header("Authorization", "Bearer " + cashier.accessToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cliente canceló\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/void", kitchenOrder.getId())
                        .with(user("kitchen").roles("KITCHEN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cliente canceló\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/{id}/void", kitchenOrder.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cliente canceló\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void voidEndpointRejectsInvalidReasonAndNonPosSourceWithStableErrors() throws Exception {
        OrderRecord pending = order("PENDING", OrderPaymentMethod.CASH, 1);
        OrderRecord whatsapp = order("PENDING", OrderPaymentMethod.CASH, 2);
        whatsapp.setOrderSource(OrderSource.WHATSAPP_AI);
        orderRepository.saveAndFlush(whatsapp);
        TestActor actor = insertActor("CASHIER");

        mockMvc.perform(put("/api/orders/{id}/void", pending.getId()).header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_VOID_REQUEST"));
        mockMvc.perform(put("/api/orders/{id}/void", pending.getId()).header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_VOID_REQUEST"));
        mockMvc.perform(put("/api/orders/{id}/void", pending.getId()).header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"%s\"}".formatted("x".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_VOID_REQUEST"));
        mockMvc.perform(put("/api/orders/{id}/void", whatsapp.getId()).header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Cliente canceló\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_OPERATION_NOT_SUPPORTED"));

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderLifecycleStatus.PENDING.persistedValue());
    }

    @Test
    void malformedLegacyRejectJsonIsNotClassifiedAsPosVoidValidation() throws Exception {
        OrderRecord order = order("PENDING", OrderPaymentMethod.CASH, 1);

        mockMvc.perform(post("/api/orders/{id}/reject", order.getId())
                        .with(user("kitchen").roles("KITCHEN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ORDER_INVALID_VOID_REQUEST"))));
    }

    private OrderRecord order(String status, OrderPaymentMethod paymentMethod, int minute) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("521477100" + minute);
        order.setPaymentMethod(paymentMethod);
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 11, minute));
        order.setOrderDetails("Orden de prueba");
        return orderRepository.saveAndFlush(order);
    }

    private TestActor insertActor(String role) {
        String username = "lifecycle-void-" + role.toLowerCase() + "-" + UUID.randomUUID();
        String password = "frase temporal segura para pruebas 2026";
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, ?, ?, true, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """, username, username, passwordPolicyService.encodeValidated(username, password), role);
        Long userId = jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
        AuthResponse response = authService.login(new LoginRequest(username, password, "void-test-" + UUID.randomUUID(), null, null),
                "127.0.0.1");
        return new TestActor(userId, response.accessToken());
    }

    private record TestActor(Long id, String accessToken) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
