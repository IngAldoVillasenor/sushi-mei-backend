package com.sushimei.sushimei.backend.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.businessday.CloseBusinessDayRequest;
import com.sushimei.sushimei.backend.businessday.OpenBusinessDayRequest;
import com.sushimei.sushimei.backend.entity.OrderLineKind;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.orderread.OperationalOrderReadService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        OpenSaleServiceIntegrationTest.TestInfrastructureConfiguration.class})
class OpenSaleServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");

    @Autowired private OpenSaleService openSaleService;
    @Autowired private BusinessDayService businessDayService;
    @Autowired private OperationalOrderReadService operationalOrderReadService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long ownerId;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.business_day_closures");
        jdbcTemplate.update("delete from public.business_days");
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        ownerId = insertUser("open-sale-owner");
    }

    @Test
    void requiresAnOpenBusinessDayAndPersistsExplicitCounterRevenueWithoutCreatingCatalogData() {
        OpenSaleRequest request = new OpenSaleRequest(UUID.randomUUID(), "  Venta   libre  ", new BigDecimal("50.00"),
                OrderPaymentMethod.CASH, new BigDecimal("100.00"));
        assertError(() -> openSaleService.create(ownerId, request), OpenSaleError.OPEN_SALE_BUSINESS_DAY_OPEN_REQUIRED);

        businessDayService.open(ownerId, new OpenBusinessDayRequest(new BigDecimal("10.00")));
        Integer menusBefore = jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class);
        OpenSaleResponse created = openSaleService.create(ownerId, request);
        OpenSaleResponse retry = openSaleService.create(ownerId, request);

        assertThat(created.result()).isEqualTo(OpenSaleResult.CREATED);
        assertThat(created.orderSource()).isEqualTo(OrderSource.COUNTER);
        assertThat(created.status()).isEqualTo("COMPLETED");
        assertThat(created.description()).isEqualTo("Venta libre");
        assertThat(created.total()).isEqualByComparingTo("50.00");
        assertThat(created.cashDenomination()).isEqualByComparingTo("100.00");
        assertThat(retry.result()).isEqualTo(OpenSaleResult.ALREADY_CREATED);
        assertThat(retry.id()).isEqualTo(created.id());
        assertThat(jdbcTemplate.queryForObject("select line_kind from public.order_lines", String.class)).isEqualTo("OPEN_SALE");
        assertThat(jdbcTemplate.queryForObject("select source_menu_item_id from public.order_lines", Long.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isEqualTo(menusBefore);
        assertThat(operationalOrderReadService.order(created.id()).lines()).singleElement().satisfies(line -> {
            assertThat(line.lineKind()).isEqualTo(OrderLineKind.OPEN_SALE);
            assertThat(line.sourceMenuItemId()).isNull();
            assertThat(line.name()).isEqualTo("Venta libre");
            assertThat(line.finalLineTotal()).isEqualByComparingTo("50.00");
        });

        var closed = businessDayService.close(ownerId, new CloseBusinessDayRequest(new BigDecimal("60.00")));
        assertThat(closed.completedSalesAmount()).isEqualByComparingTo("50.00");
        assertThat(closed.cashSalesAmount()).isEqualByComparingTo("50.00");
        assertThat(closed.expectedClosingCashAmount()).isEqualByComparingTo("60.00");
        assertError(() -> openSaleService.create(ownerId, new OpenSaleRequest(UUID.randomUUID(), "Después", new BigDecimal("1.00"),
                OrderPaymentMethod.CASH, new BigDecimal("1.00"))), OpenSaleError.OPEN_SALE_BUSINESS_DAY_OPEN_REQUIRED);
    }

    @Test
    void supportsCardAndTransferButRejectsNonCashTenderAndIdempotencyReuseByAnotherActor() {
        businessDayService.open(ownerId, new OpenBusinessDayRequest(BigDecimal.ZERO));
        OpenSaleResponse card = openSaleService.create(ownerId, new OpenSaleRequest(UUID.randomUUID(), "Tarjeta", new BigDecimal("20.00"),
                OrderPaymentMethod.CARD, null));
        OpenSaleResponse transfer = openSaleService.create(ownerId, new OpenSaleRequest(UUID.randomUUID(), "Transferencia", new BigDecimal("30.00"),
                OrderPaymentMethod.TRANSFER, null));
        assertThat(card.paymentMethod()).isEqualTo(OrderPaymentMethod.CARD);
        assertThat(transfer.paymentMethod()).isEqualTo(OrderPaymentMethod.TRANSFER);
        assertError(() -> openSaleService.create(ownerId, new OpenSaleRequest(UUID.randomUUID(), "Inválida", new BigDecimal("5.001"),
                OrderPaymentMethod.CASH, null)), OpenSaleError.OPEN_SALE_INVALID);
        assertError(() -> openSaleService.create(ownerId, new OpenSaleRequest(UUID.randomUUID(), "Inválida", new BigDecimal("5.00"),
                OrderPaymentMethod.CARD, new BigDecimal("5.00"))), OpenSaleError.OPEN_SALE_INVALID);

        UUID requestId = UUID.randomUUID();
        openSaleService.create(ownerId, new OpenSaleRequest(requestId, "Misma", new BigDecimal("9.00"), OrderPaymentMethod.CASH,
                new BigDecimal("9.00")));
        assertError(() -> openSaleService.create(insertUser("open-sale-other"),
                new OpenSaleRequest(requestId, "Misma", new BigDecimal("9.00"), OrderPaymentMethod.CASH,
                        new BigDecimal("9.00"))),
                OpenSaleError.OPEN_SALE_IDEMPOTENCY_CONFLICT);
    }

    private Long insertUser(String username) {
        String normalizedUsername = username + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, ?, 'OWNER', true, 0, ?, ?, ?, 0)
                """, normalizedUsername, normalizedUsername, "{bcrypt}not-used", NOW, NOW, NOW);
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, normalizedUsername);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, OpenSaleError error) {
        assertThatThrownBy(action).isInstanceOf(OpenSaleException.class)
                .extracting(exception -> ((OpenSaleException) exception).getError()).isEqualTo(error);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
