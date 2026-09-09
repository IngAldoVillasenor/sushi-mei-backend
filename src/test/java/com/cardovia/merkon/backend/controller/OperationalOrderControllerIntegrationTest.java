package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderRecord;
import com.cardovia.merkon.backend.repository.OrderRepository;
import com.cardovia.merkon.backend.security.AuthService;
import com.cardovia.merkon.backend.security.LoginRequest;
import com.cardovia.merkon.backend.security.PasswordPolicyService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({com.cardovia.merkon.backend.security.SecurityTestKeyConfiguration.class,
        OperationalOrderControllerIntegrationTest.TestInfrastructureConfiguration.class})
class OperationalOrderControllerIntegrationTest {

    @Test
    void historyEndpointReturnsStablePaginationContract() throws Exception {
        order("COMPLETED", 1);

        mockMvc.perform(get("/api/v1/orders?page=0&size=50")
                .with(legacyJwt("manager", "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LegacyBusinessResolver legacyBusinessResolver;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void versionedOperationalReadsRequireAuthenticationAndAllowAllOperationalRoles() throws Exception {
        OrderRecord order = order("PENDING", 1);

        mockMvc.perform(get("/api/v1/orders/active"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders/active").with(legacyJwt("owner", "OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/active").with(legacyJwt("manager", "MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/active").with(legacyJwt("cashier", "CASHIER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/active").with(legacyJwt("kitchen", "KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(legacyJwt("owner", "OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(legacyJwt("manager", "MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(legacyJwt("cashier", "CASHIER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(legacyJwt("kitchen", "KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders").with(legacyJwt("kitchen", "KITCHEN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailAndMissingOrderUseStableDtoAndErrorContracts() throws Exception {
        OrderRecord order = order("PENDING", 1);

        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(legacyJwt("cashier", "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.getId()))
                .andExpect(jsonPath("$.createdAt").value("2026-08-11T12:01:00Z"))
                .andExpect(jsonPath("$.legacyOrderDetails").value("Detalle operativo"))
                .andExpect(jsonPath("$.requestFingerprint").doesNotExist())
                .andExpect(jsonPath("$.orderLines").doesNotExist());
        mockMvc.perform(get("/api/v1/orders/active").with(legacyJwt("cashier", "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdAt").value("2026-08-11T12:01:00Z"));
        mockMvc.perform(get("/api/v1/orders/{id}", 999999L).with(legacyJwt("manager", "MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private OrderRecord order(String status, int minute) {
        OrderRecord order = new OrderRecord();
        order.setBusiness(legacyBusinessResolver.requireLegacyBusiness());
        order.setPhoneNumber("521477200" + minute);
        order.setPaymentMethod(OrderPaymentMethod.CASH);
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 12, minute));
        order.setOrderDetails("Detalle operativo");
        return orderRepository.saveAndFlush(order);
    }

    private RequestPostProcessor legacyJwt(String subject, String role) {
        String username = "operational-" + subject + "-" + UUID.randomUUID();
        String password = "frase temporal segura para pruebas 2026";
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, ?, ?, true, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """, username, username, passwordPolicyService.encodeValidated(username, password), role);
        Long userId = jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
        jdbcTemplate.update("""
                insert into public.business_memberships (user_id, business_id, role, created_at, updated_at, version)
                values (?, ?, ?, current_timestamp, current_timestamp, 0)
                """, userId, legacyBusinessResolver.requireLegacyBusinessId(), role);
        String accessToken = authService.login(new LoginRequest(username, password, "operational-device-" + UUID.randomUUID(), null, null),
                "127.0.0.1").accessToken();
        return request -> {
            request.addHeader("Authorization", "Bearer " + accessToken);
            return request;
        };
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

    @Test
    void historyEndpointRejectsCashier() throws Exception {
        mockMvc.perform(get("/api/v1/orders?page=0&size=50").with(legacyJwt("user", "CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyEndpointRejectsKitchen() throws Exception {
        mockMvc.perform(get("/api/v1/orders?page=0&size=50").with(legacyJwt("user", "KITCHEN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyEndpointAllowsOwner() throws Exception {
        mockMvc.perform(get("/api/v1/orders?page=0&size=50").with(legacyJwt("user", "OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void historyEndpointAllowsManager() throws Exception {
        mockMvc.perform(get("/api/v1/orders?page=0&size=50").with(legacyJwt("user", "MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void analyticsEndpointRejectsCashier() throws Exception {
        mockMvc.perform(get("/api/v1/orders/analytics?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z").with(legacyJwt("user", "CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void analyticsEndpointRejectsKitchen() throws Exception {
        mockMvc.perform(get("/api/v1/orders/analytics?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z").with(legacyJwt("user", "KITCHEN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void analyticsEndpointAllowsOwner() throws Exception {
        mockMvc.perform(get("/api/v1/orders/analytics?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z").with(legacyJwt("user", "OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void analyticsEndpointAllowsManager() throws Exception {
        mockMvc.perform(get("/api/v1/orders/analytics?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z").with(legacyJwt("user", "MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void analyticsEndpointRejectsInvalidRange() throws Exception {
        mockMvc.perform(get("/api/v1/orders/analytics?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z").with(legacyJwt("user", "OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void analyticsEndpointRejectsEqualRange() throws Exception {
        mockMvc.perform(get("/api/v1/orders/analytics?from=2026-01-01T00:00:00Z&to=2026-01-01T00:00:00Z").with(legacyJwt("user", "OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }
}
