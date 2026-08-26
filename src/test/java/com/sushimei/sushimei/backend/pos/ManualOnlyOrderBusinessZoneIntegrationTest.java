package com.sushimei.sushimei.backend.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderLineKind;
import com.sushimei.sushimei.backend.promotion.TemporalPromotionQuoteService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "sushimei.business-zone=UTC")
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        ManualOnlyOrderBusinessZoneIntegrationTest.TestInfrastructureConfiguration.class})
class ManualOnlyOrderBusinessZoneIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");

    @Autowired private ManualPosOrderService manualPosOrderService;
    @Autowired private TemporalPromotionQuoteService promotionQuoteService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_selection_component_omissions");
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void manualOnlyOrderUsesTheConfiguredBusinessZoneInsteadOfACatalogSpecificLiteral() {
        Long userId = insertUser("manual-zone");

        ManualPosOrderResponse created = manualPosOrderService.create(userId,
                new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.PICKUP, OrderPaymentMethod.CARD,
                        null, "Ana", null, List.of(),
                        List.of(new ManualPricedLineRequest("manual", "Manual service", 1,
                                new BigDecimal("12.00")))));

        assertThat(promotionQuoteService.businessTimeZone()).isEqualTo("UTC");
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.lines()).singleElement().extracting(ManualPosOrderLineResponse::lineKind)
                .isEqualTo(OrderLineKind.MANUAL_PRICED_LINE);
    }

    private Long insertUser(String username) {
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, '{bcrypt}test', 'CASHIER', true, 0, ?, ?, ?, 0)
                """, username, username, NOW, NOW, NOW);
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
