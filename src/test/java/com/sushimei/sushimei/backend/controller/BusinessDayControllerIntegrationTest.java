package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.security.ApplicationRole;
import com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, BusinessDayControllerIntegrationTest.TestInfrastructureConfiguration.class})
class BusinessDayControllerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.business_day_closures");
        jdbcTemplate.update("delete from public.business_days");
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.app_users");
    }

    @Test
    void ownerAndManagerCanOpenReadAndCloseButCashierAndKitchenCannot() throws Exception {
        AuthenticatedUser owner = insertUser("business-owner", ApplicationRole.OWNER);
        AuthenticatedUser manager = insertUser("business-manager", ApplicationRole.MANAGER);
        AuthenticatedUser cashier = insertUser("business-cashier", ApplicationRole.CASHIER);
        AuthenticatedUser kitchen = insertUser("business-kitchen", ApplicationRole.KITCHEN);

        mockMvc.perform(get("/api/v1/business-days/current"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/business-days/current").with(cashier.jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/business-days/current").with(kitchen.jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/business-days/current").with(owner.jwt()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/business-days/open").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingCashAmount\":1000.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingCashAmount").value(1000.00));

        mockMvc.perform(get("/api/v1/business-days/current").with(manager.jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-12"));

        mockMvc.perform(post("/api/v1/business-days/current/close").with(manager.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualClosingCashAmount\":1000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.cashDifferenceAmount").value(0.00))
                .andExpect(jsonPath("$.closureId").isNumber())
                .andExpect(jsonPath("$.closureNumber").value(1));
        Long firstClosureId = jdbcTemplate.queryForObject(
                "select id from public.business_day_closures where close_number = 1", Long.class);

        mockMvc.perform(get("/api/v1/business-days/current").with(manager.jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.openingCashAmount").value(1000.00))
                .andExpect(jsonPath("$.completedSalesAmount").value(0.00))
                .andExpect(jsonPath("$.expectedClosingCashAmount").value(1000.00))
                .andExpect(jsonPath("$.actualClosingCashAmount").value(1000.00))
                .andExpect(jsonPath("$.cashDifferenceAmount").value(0.00))
                .andExpect(jsonPath("$.closureId").value(firstClosureId))
                .andExpect(jsonPath("$.closureNumber").value(1));

        mockMvc.perform(post("/api/v1/business-days/current/reopen").with(owner.jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingCashAmount").value(1000.00))
                .andExpect(jsonPath("$.completedSalesAmount").doesNotExist())
                .andExpect(jsonPath("$.closureId").doesNotExist())
                .andExpect(jsonPath("$.closureNumber").doesNotExist());

        mockMvc.perform(post("/api/v1/business-days/current/close").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualClosingCashAmount\":1000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/business-days/current/reopen").with(manager.jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/api/v1/business-days/current/reopen").with(cashier.jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/business-days/current/reopen").with(kitchen.jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidAmountsAndClosingWithoutAnOpenDayUseStableBusinessDayErrors() throws Exception {
        AuthenticatedUser owner = insertUser("business-owner-invalid", ApplicationRole.OWNER);

        mockMvc.perform(post("/api/v1/business-days/current/reopen").with(owner.jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_DAY_REOPEN_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/business-days/current/close").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualClosingCashAmount\":0.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_DAY_NOT_OPEN"));
        mockMvc.perform(post("/api/v1/business-days/open").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingCashAmount\":-1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_DAY_INVALID"));
        mockMvc.perform(post("/api/v1/business-days/open").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingCashAmount\":0.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/business-days/current/reopen").with(owner.jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_DAY_NOT_CLOSED"));
    }

    @Test
    void currentKeepsAnAccidentallyUnclosedOlderBusinessDayDiscoverable() throws Exception {
        AuthenticatedUser owner = insertUser("business-owner-older-open", ApplicationRole.OWNER);
        jdbcTemplate.update("""
                insert into public.business_days (business_date, status, opening_cash_amount, opened_at,
                    opened_by_user_id, open_guard, version)
                values ('2026-08-11', 'OPEN', 100.00, ?, ?, 1, 0)
                """, NOW, owner.id());

        mockMvc.perform(get("/api/v1/business-days/current").with(owner.jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-11"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingCashAmount").value(100.00));
    }

    @Test
    void closingWithAnActiveOrderReturnsTheStableConflictAndKeepsTheDayOpen() throws Exception {
        AuthenticatedUser owner = insertUser("business-owner-active-order", ApplicationRole.OWNER);
        mockMvc.perform(post("/api/v1/business-days/open").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingCashAmount\":100.00}"))
                .andExpect(status().isCreated());
        activeOrder();

        mockMvc.perform(post("/api/v1/business-days/current/close").with(owner.jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualClosingCashAmount\":100.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_DAY_HAS_ACTIVE_ORDERS"));

        mockMvc.perform(get("/api/v1/business-days/current").with(owner.jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.completedSalesAmount").doesNotExist());
    }

    private void activeOrder() {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("5214770000000");
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setPaymentMethod(OrderPaymentMethod.CASH);
        order.setTotalAmount(79.00d);
        order.setTotalAmountAmount(new java.math.BigDecimal("79.00"));
        order.setStatus("PREPARING");
        order.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        order.setOrderDetails("Operational test evidence");
        orderRepository.saveAndFlush(order);
    }

    private AuthenticatedUser insertUser(String username, ApplicationRole role) {
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, ?, ?, true, 0, ?, ?, ?, 0)
                """, username, username, "{bcrypt}not-used", role.name(), NOW, NOW, NOW);
        Long id = jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.auth_sessions (id, user_id, device_id, current_refresh_token_hash, created_at,
                    last_refreshed_at, absolute_expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, sessionId, id, "business-device-" + username, String.format("%064d", id), NOW, NOW,
                NOW.plusSeconds(900));
        return new AuthenticatedUser(id, sessionId, role, username);
    }

    private record AuthenticatedUser(Long id, UUID sessionId, ApplicationRole role, String username) {
        JwtRequestPostProcessor jwt() {
            return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(token -> token.subject(id.toString())
                            .claim("sid", sessionId.toString())
                            .claim("role", role.name())
                            .claim("username", username))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

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
