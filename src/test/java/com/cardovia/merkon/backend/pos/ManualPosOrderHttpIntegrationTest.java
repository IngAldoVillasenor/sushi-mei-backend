package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.catalog.CreateMenuItemRequest;
import com.cardovia.merkon.backend.catalog.MenuCatalogService;
import com.cardovia.merkon.backend.catalog.MenuItemResponse;
import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.businessday.CloseBusinessDayRequest;
import com.cardovia.merkon.backend.businessday.OpenBusinessDayRequest;
import com.cardovia.merkon.backend.security.SecurityTestKeyConfiguration;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, ManualPosOrderHttpIntegrationTest.TestInfrastructureConfiguration.class})
class ManualPosOrderHttpIntegrationTest {

    private static final String USERNAME = "http-cashier";
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MenuCatalogService menuCatalogService;

    @Autowired
    private BusinessDayService businessDayService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.business_day_closures");
        jdbcTemplate.update("delete from public.business_days");
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.app_users");
        jdbcTemplate.update("delete from public.promotion_targets");
        jdbcTemplate.update("delete from public.promotion_weekdays");
        jdbcTemplate.update("delete from public.promotions");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_default_components");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
    }

    @Test
    void postManualOrderSerializesCreatedAtAsTheControlledUtcInstant() throws Exception {
        MenuItemResponse california = menuCatalogService.create(new CreateMenuItemRequest(
                "California", null, "Rollos", new BigDecimal("79.00"), true, true, 0));
        Long userId = insertCashier();
        UUID sessionId = insertActiveSession(userId);
        UUID requestId = UUID.randomUUID();
        String request = """
                {"requestId":"%s","fulfillmentType":"PICKUP","paymentMethod":"CASH",
                "pickupName":"Ana","lines":[{"lineKey":"line-1","menuItemId":%d,
                "quantity":1,"groups":[],"rewardConfigurations":[]}]}
                """.formatted(requestId, california.id());

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token
                                        .subject(userId.toString())
                                        .claim("sid", sessionId.toString())
                                        .claim("role", "CASHIER")
                                        .claim("username", USERNAME))
                                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.createdAt").value(TestInfrastructureConfiguration.ORDER_TIME.toString()));
    }

    @Test
    void postManualDeliveryOrderAcceptsExplicitOnDeliveryTimingWithoutAPrematurePaymentMethod() throws Exception {
        MenuItemResponse california = menuCatalogService.create(new CreateMenuItemRequest(
                "California", null, "Rollos", new BigDecimal("79.00"), true, true, 0));
        Long userId = insertCashier();
        UUID sessionId = insertActiveSession(userId);
        String request = """
                {"requestId":"%s","fulfillmentType":"DELIVERY","paymentTiming":"ON_DELIVERY",
                "deliveryAddress":"Calle Principal 123","lines":[{"lineKey":"line-1","menuItemId":%d,
                "quantity":1,"groups":[],"rewardConfigurations":[]}]}
                """.formatted(UUID.randomUUID(), california.id());

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token.subject(userId.toString()).claim("sid", sessionId.toString())
                                        .claim("role", "CASHIER").claim("username", USERNAME))
                                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.paymentTiming").value("ON_DELIVERY"))
                .andExpect(jsonPath("$.requiresPaymentCollection").value(true))
                .andExpect(jsonPath("$.paymentMethod").doesNotExist())
                .andExpect(jsonPath("$.cashDenomination").doesNotExist());
    }

    @Test
    void postManualOrderReturnsBusinessDayClosedWithoutChangingTheCloseSnapshot() throws Exception {
        MenuItemResponse california = menuCatalogService.create(new CreateMenuItemRequest(
                "California", null, "Rollos", new BigDecimal("79.00"), true, true, 0));
        Long userId = insertCashier();
        UUID sessionId = insertActiveSession(userId);
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("100.00")));
        businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("100.00")));
        String request = """
                {"requestId":"%s","fulfillmentType":"PICKUP","paymentMethod":"CASH",
                "pickupName":"Ana","lines":[{"lineKey":"line-1","menuItemId":%d,
                "quantity":1,"groups":[],"rewardConfigurations":[]}]}
                """.formatted(UUID.randomUUID(), california.id());

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token
                                        .subject(userId.toString())
                                        .claim("sid", sessionId.toString())
                                        .claim("role", "CASHIER")
                                        .claim("username", USERNAME))
                                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_DAY_CLOSED"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select expected_closing_cash_amount from public.business_days", BigDecimal.class))
                .isEqualByComparingTo("100.00");
    }

    private Long insertCashier() {
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, ?, 'CASHIER', true, 0, ?, ?, ?, 0)
                """, USERNAME, "Caja HTTP", "{bcrypt}not-used-by-this-http-contract-test",
                TestInfrastructureConfiguration.ORDER_TIME, TestInfrastructureConfiguration.ORDER_TIME,
                TestInfrastructureConfiguration.ORDER_TIME);
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, USERNAME);
    }

    private UUID insertActiveSession(Long userId) {
        Long businessId = jdbcTemplate.queryForObject("select id from public.businesses where name = 'Sushi Mei'", Long.class);
        jdbcTemplate.update("""
                insert into public.business_memberships (user_id, business_id, role, created_at, updated_at, version)
                values (?, ?, 'CASHIER', ?, ?, 0)
                """, userId, businessId, TestInfrastructureConfiguration.ORDER_TIME,
                TestInfrastructureConfiguration.ORDER_TIME);
        Long membershipId = jdbcTemplate.queryForObject("""
                select id from public.business_memberships where user_id = ? and business_id = ?
                """, Long.class, userId, businessId);
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.auth_sessions (id, user_id, active_membership_id, device_id, current_refresh_token_hash, created_at,
                    last_refreshed_at, absolute_expires_at)
                values (?, ?, ?, 'http-device', ?, ?, ?, ?)
                """, sessionId, userId, membershipId, "a".repeat(64), TestInfrastructureConfiguration.ORDER_TIME,
                TestInfrastructureConfiguration.ORDER_TIME, TestInfrastructureConfiguration.ORDER_TIME.plusSeconds(900));
        return sessionId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        static final Instant ORDER_TIME = Instant.parse("2026-08-11T12:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(ORDER_TIME, ZoneOffset.UTC);
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
