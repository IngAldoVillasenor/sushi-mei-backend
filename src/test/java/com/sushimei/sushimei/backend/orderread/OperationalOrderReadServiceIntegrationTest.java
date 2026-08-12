package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
import com.sushimei.sushimei.backend.catalog.MenuItemResponse;
import com.sushimei.sushimei.backend.catalog.UpdateMenuItemRequest;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderLineKind;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        OperationalOrderReadServiceIntegrationTest.TestInfrastructureConfiguration.class})
class OperationalOrderReadServiceIntegrationTest {

    @Autowired
    private OperationalOrderReadService operationalOrderReadService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MenuCatalogService menuCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.menu_items");
        jdbcTemplate.update("delete from public.app_users");
    }

    @Test
    void activeOrdersAreLightweightDeterministicAndSafeForLegacyAndManualMetadata() {
        OrderRecord legacy = legacyOrder("PENDING", 9);
        OrderRecord manual = manualOrder("PENDING_VALIDATION", 10, null);
        OrderRecord preparing = legacyOrder("PREPARING", 11);
        legacyOrder("COMPLETED", 12);
        legacyOrder("CANCELLED_CLARIFICATION", 13);

        List<OperationalOrderSummaryResponse> active = operationalOrderReadService.activeOrders();

        assertThat(active).extracting(OperationalOrderSummaryResponse::id)
                .containsExactly(legacy.getId(), manual.getId(), preparing.getId());
        assertThat(active.get(0)).satisfies(summary -> {
            assertThat(summary.orderSource()).isNull();
            assertThat(summary.fulfillmentType()).isNull();
            assertThat(summary.paymentMethod()).isNull();
            assertThat(summary.phoneNumber()).isEqualTo("5214770000009");
            assertThat(summary.total()).isEqualByComparingTo("10.50");
            assertThat(summary.requiresPaymentValidation()).isFalse();
            assertThat(summary.structuredLinesAvailable()).isFalse();
        });
        assertThat(active.get(1)).satisfies(summary -> {
            assertThat(summary.orderSource()).isEqualTo(OrderSource.ANDROID_MANUAL);
            assertThat(summary.phoneNumber()).isNull();
            assertThat(summary.requiresPaymentValidation()).isTrue();
            assertThat(summary.structuredLinesAvailable()).isTrue();
        });
    }

    @Test
    void detailUsesPersistedManualLinePromotionAndParentLinkedConfigurationEvidence() {
        MenuItemResponse currentCatalogItem = menuCatalogService.create(new CreateMenuItemRequest(
                "California actual", null, "Rollos", new BigDecimal("79.00"), true, true, 0));
        OrderRecord order = manualOrder("PENDING", 10, currentCatalogItem.id());

        OperationalOrderDetailResponse initial = operationalOrderReadService.order(order.getId());

        assertThat(initial.requestId()).isNotNull();
        assertThat(initial.orderSource()).isEqualTo(OrderSource.ANDROID_MANUAL);
        assertThat(initial.createdByUserId()).isEqualTo(1L);
        assertThat(initial.phoneNumber()).isNull();
        assertThat(initial.total()).isEqualByComparingTo("94.00");
        assertThat(initial.lines()).hasSize(2);
        OperationalOrderLineResponse paid = initial.lines().get(0);
        OperationalOrderLineResponse reward = initial.lines().get(1);
        assertThat(paid.lineKind()).isEqualTo(OrderLineKind.PAID);
        assertThat(paid.lineKey()).isEqualTo("line-1");
        assertThat(paid.name()).isEqualTo("California histórico");
        assertThat(paid.catalogBaseUnitPrice()).isEqualByComparingTo("79.00");
        assertThat(paid.finalLineTotal()).isEqualByComparingTo("79.00");
        assertThat(paid.promotion()).extracting(OperationalPromotionSnapshotResponse::name).isEqualTo("Jueves histórico");
        assertThat(reward.lineKind()).isEqualTo(OrderLineKind.PROMOTION_REWARD);
        assertThat(reward.rewardOrdinal()).isEqualTo(1);
        assertThat(reward.sourcePaidLineId()).isEqualTo(paid.id());
        assertThat(reward.finalLineTotal()).isEqualByComparingTo("15.00");
        assertThat(reward.configuration()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.itemName()).isEqualTo("Olas recompensa histórica");
            assertThat(snapshot.priceAdjustment()).isEqualByComparingTo("15.00");
        });
        assertThat(paid.configuration()).hasSize(2);
        OperationalOrderSelectionSnapshotResponse selectedRoll = paid.configuration().get(0);
        OperationalOrderSelectionSnapshotResponse nestedTopping = paid.configuration().get(1);
        assertThat(selectedRoll.parentSelectionSnapshotId()).isNull();
        assertThat(nestedTopping.parentSelectionSnapshotId()).isEqualTo(selectedRoll.id());
        assertThat(nestedTopping.itemName()).isEqualTo("Olas histórica");

        menuCatalogService.update(currentCatalogItem.id(), new UpdateMenuItemRequest(
                "California actual cambiada", null, "Rollos", new BigDecimal("120.00"),
                true, true, true, 0, currentCatalogItem.version()));

        OperationalOrderDetailResponse afterLiveCatalogChange = operationalOrderReadService.order(order.getId());
        assertThat(afterLiveCatalogChange.lines().get(0).name()).isEqualTo("California histórico");
        assertThat(afterLiveCatalogChange.lines().get(0).catalogBaseUnitPrice()).isEqualByComparingTo("79.00");
        assertThat(afterLiveCatalogChange.lines().get(0).configuration().get(1).itemName())
                .isEqualTo("Olas histórica");
        assertThat(afterLiveCatalogChange.lines().get(1).promotion().name()).isEqualTo("Jueves histórico");
    }

    @Test
    void historicalLegacyOrderWithoutStructuredLinesRemainsReadableAndMissingOrderIsSafe() {
        OrderRecord legacy = legacyOrder("PENDING", 9);

        OperationalOrderDetailResponse detail = operationalOrderReadService.order(legacy.getId());

        assertThat(detail.orderSource()).isNull();
        assertThat(detail.requestId()).isNull();
        assertThat(detail.createdByUserId()).isNull();
        assertThat(detail.lines()).isEmpty();
        assertThat(detail.total()).isEqualByComparingTo("10.50");
        assertThat(detail.legacyOrderDetails()).isEqualTo("Detalle legado 9");
        assertThatThrownBy(() -> operationalOrderReadService.order(999999L))
                .isInstanceOf(OperationalOrderReadException.class);
    }

    @Test
    void whatsappAndCounterProvenanceRemainReadableWithoutManualOrderAssumptions() {
        OrderRecord whatsapp = legacyOrder("PENDING", 9);
        whatsapp.setOrderSource(OrderSource.WHATSAPP_AI);
        orderRepository.saveAndFlush(whatsapp);
        OrderRecord counter = legacyOrder("PENDING", 10);
        counter.setOrderSource(OrderSource.COUNTER);
        orderRepository.saveAndFlush(counter);

        assertThat(operationalOrderReadService.order(whatsapp.getId()).orderSource())
                .isEqualTo(OrderSource.WHATSAPP_AI);
        assertThat(operationalOrderReadService.order(counter.getId()).orderSource())
                .isEqualTo(OrderSource.COUNTER);
    }

    private OrderRecord legacyOrder(String status, int minute) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("521477000000" + minute);
        order.setTotalAmount(10.50d);
        order.setTotalAmountAmount(new BigDecimal("10.50"));
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 8, minute));
        order.setOrderDetails("Detalle legado " + minute);
        return orderRepository.saveAndFlush(order);
    }

    private OrderRecord manualOrder(String status, int minute, Long sourceMenuItemId) {
        long menuItemId = sourceMenuItemId == null ? 101L : sourceMenuItemId;
        ensureManualUser();
        OrderRecord order = new OrderRecord();
        order.setClientRequestId(UUID.randomUUID());
        order.setCreatedByUserId(1L);
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setFulfillmentType(OrderFulfillmentType.PICKUP);
        order.setPaymentMethod(OrderPaymentMethod.TRANSFER);
        order.setPickupName("Ana");
        order.setPhoneNumber(null);
        order.setTotalAmount(94.00d);
        order.setTotalAmountAmount(new BigDecimal("94.00"));
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 8, minute));
        order.setOrderDetails("Detalle POS histórico");

        OrderLineRecord paid = OrderLineRecord.createManualPaid("line-1", menuItemId, 1,
                "California histórico", 1, new BigDecimal("79.00"), new BigDecimal("79.00"),
                new BigDecimal("0.00"), new BigDecimal("79.00"), new BigDecimal("79.00"),
                700L, "Jueves histórico", "BUY_X_GET_Y_SAME_ITEM");
        OrderLineSelectionSnapshot selectedRoll = OrderLineSelectionSnapshot.create(null, 11L, "Rollo histórico", 1,
                menuItemId, "California histórico", 1, new BigDecimal("79.00"), new BigDecimal("0.00"));
        OrderLineSelectionSnapshot nestedTopping = OrderLineSelectionSnapshot.create(selectedRoll, 12L,
                "Topping histórico", 1, 300L, "Olas histórica", 1, new BigDecimal("15.00"),
                new BigDecimal("15.00"));
        paid.addSelectionSnapshot(selectedRoll);
        paid.addSelectionSnapshot(nestedTopping);
        order.addOrderLine(paid);

        OrderLineRecord reward = OrderLineRecord.createPromotionReward(paid, 2, "California histórico",
                new BigDecimal("79.00"), new BigDecimal("15.00"), new BigDecimal("15.00"),
                new BigDecimal("15.00"), 700L, "Jueves histórico", "BUY_X_GET_Y_SAME_ITEM", 1);
        reward.addSelectionSnapshot(OrderLineSelectionSnapshot.create(null, 12L, "Topping recompensa", 1,
                300L, "Olas recompensa histórica", 1, new BigDecimal("15.00"), new BigDecimal("15.00")));
        order.addOrderLine(reward);
        return orderRepository.saveAndFlush(order);
    }

    private void ensureManualUser() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from public.app_users where id = 1", Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.update("""
                    insert into public.app_users (id, username, display_name, password_hash, role, active,
                        failed_login_attempts, password_changed_at, created_at, updated_at, version)
                    values (1, 'operational-reader', 'Operational Reader', '{bcrypt}test', 'CASHIER', true,
                        0, current_timestamp, current_timestamp, current_timestamp, 0)
                    """);
        }
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
