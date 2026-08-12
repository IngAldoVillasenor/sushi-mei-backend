package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuSelectionGroupRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuSelectionRuleRequest;
import com.sushimei.sushimei.backend.catalog.CatalogConfigurationService;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
import com.sushimei.sushimei.backend.catalog.MenuItemResponse;
import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest;
import com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionRequest;
import com.sushimei.sushimei.backend.catalog.MenuSelectionGroupResponse;
import com.sushimei.sushimei.backend.catalog.SelectionPricingPolicy;
import com.sushimei.sushimei.backend.catalog.SelectionRuleTargetType;
import com.sushimei.sushimei.backend.catalog.UpdateMenuItemRequest;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderLineKind;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.promotion.CreatePromotionRequest;
import com.sushimei.sushimei.backend.promotion.PromotionBenefitType;
import com.sushimei.sushimei.backend.promotion.PromotionService;
import com.sushimei.sushimei.backend.promotion.PromotionTargetRequest;
import com.sushimei.sushimei.backend.promotion.PromotionTargetType;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import com.sushimei.sushimei.backend.promotion.PromotionRewardConfigurationRequest;
import com.sushimei.sushimei.backend.promotion.PromotionException;
import com.sushimei.sushimei.backend.promotion.PromotionResponse;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        ManualPosOrderServiceIntegrationTest.TestInfrastructureConfiguration.class})
class ManualPosOrderServiceIntegrationTest {

    @Autowired private ManualPosOrderService manualPosOrderService;
    @Autowired private MenuCatalogService menuCatalogService;
    @Autowired private CatalogConfigurationService catalogConfigurationService;
    @Autowired private PromotionService promotionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.promotion_targets");
        jdbcTemplate.update("delete from public.promotion_weekdays");
        jdbcTemplate.update("delete from public.promotions");
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
        TestClock.set(Instant.parse("2026-08-10T18:00:00Z"));
    }

    @Test
    void createsExactCartlessManualOrderAndReturnsExistingOrderForSafeRetry() {
        MenuItemResponse california = item("California", "79.00");
        Long userId = insertUser("cashier-a");
        UUID requestId = UUID.randomUUID();
        ManualPosOrderRequest request = new ManualPosOrderRequest(requestId, OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.CASH, null, "Ana", new BigDecimal("200.00"),
                List.of(new PromotionQuoteLineRequest("  line  ", california.id(), 2, List.of(), List.of())));

        ManualPosOrderResponse created = manualPosOrderService.create(userId, request);
        ManualPosOrderRequest retryWithIrrelevantCash = new ManualPosOrderRequest(requestId, OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.CASH, null, "Ana", new BigDecimal("999.00"), request.lines());
        ManualPosOrderResponse retry = manualPosOrderService.create(userId, retryWithIrrelevantCash);

        assertThat(created.result()).isEqualTo(ManualOrderResult.CREATED);
        assertThat(created.orderSource()).isEqualTo(OrderSource.ANDROID_MANUAL);
        assertThat(created.createdByUserId()).isEqualTo(userId);
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.createdAt()).isEqualTo(TestClock.NOW.get());
        assertThat(created.cashDenomination()).isNull();
        assertThat(created.total()).isEqualByComparingTo("158.00");
        assertThat(created.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineKind()).isEqualTo(OrderLineKind.PAID);
            assertThat(line.lineKey()).isEqualTo("line");
            assertThat(line.sourceMenuItemId()).isEqualTo(california.id());
            assertThat(line.finalUnitAmount()).isEqualByComparingTo("79.00");
            assertThat(line.finalLineTotal()).isEqualByComparingTo("158.00");
        });
        assertThat(retry.result()).isEqualTo(ManualOrderResult.ALREADY_CREATED);
        assertThat(retry.id()).isEqualTo(created.id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select source_cart_item_id from public.order_lines", Long.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select client_line_key from public.order_lines", String.class)).isEqualTo("line");
        assertThat(jdbcTemplate.queryForObject("select cash_denomination from public.orders", BigDecimal.class)).isNull();
        assertThatThrownBy(() -> manualPosOrderService.create(insertUser("cashier-b"), request))
                .isInstanceOf(ManualPosOrderException.class)
                .extracting(exception -> ((ManualPosOrderException) exception).getError())
                .isEqualTo(ManualPosOrderError.ORDER_IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> manualPosOrderService.create(userId, request(requestId, california.id(), 3)))
                .isInstanceOf(ManualPosOrderException.class)
                .extracting(exception -> ((ManualPosOrderException) exception).getError())
                .isEqualTo(ManualPosOrderError.ORDER_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void transferStartsPendingValidationAndUsesTheQuoteInstantForCreatedAt() {
        MenuItemResponse california = item("California", "79.00");
        Instant quoteInstant = Instant.parse("2026-08-10T23:59:59Z");
        TestClock.setThenAdvance(quoteInstant, quoteInstant.plusSeconds(1));
        ManualPosOrderRequest request = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.TRANSFER, null, "Ana", new BigDecimal("200.00"),
                List.of(new PromotionQuoteLineRequest("transfer-line", california.id(), 1, List.of(), List.of())));

        ManualPosOrderResponse created = manualPosOrderService.create(insertUser("cashier-transfer"), request);

        assertThat(created.status()).isEqualTo("PENDING_VALIDATION");
        assertThat(created.createdAt()).isEqualTo(quoteInstant);
        assertThat(created.cashDenomination()).isNull();
    }

    @Test
    void pickupCashDoesNotRequireOrPersistDenominationAndPickupCardRemainsValid() {
        MenuItemResponse california = item("California", "79.00");
        Long userId = insertUser("cashier-pickup-cash");
        ManualPosOrderRequest cashWithoutDenomination = new ManualPosOrderRequest(UUID.randomUUID(),
                OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(new PromotionQuoteLineRequest("cash", california.id(), 1, List.of(), List.of())));
        ManualPosOrderRequest cardWithLegacyCash = new ManualPosOrderRequest(UUID.randomUUID(),
                OrderFulfillmentType.PICKUP, OrderPaymentMethod.CARD, null, "Ana", new BigDecimal("200.00"),
                List.of(new PromotionQuoteLineRequest("card", california.id(), 1, List.of(), List.of())));

        ManualPosOrderResponse cash = manualPosOrderService.create(userId, cashWithoutDenomination);
        ManualPosOrderResponse card = manualPosOrderService.create(userId, cardWithLegacyCash);

        assertThat(cash.cashDenomination()).isNull();
        assertThat(card.cashDenomination()).isNull();
        assertThat(card.paymentMethod()).isEqualTo(OrderPaymentMethod.CARD);
        assertThat(card.status()).isEqualTo("PENDING");
    }

    @Test
    void deliveryCashRequiresEnoughServerAuthoritativeDenominationAfterQuote() {
        MenuItemResponse california = item("California", "79.00");
        Long userId = insertUser("cashier-delivery-cash");

        assertError(() -> manualPosOrderService.create(userId, deliveryCashRequest(UUID.randomUUID(), california.id(), null)),
                ManualPosOrderError.ORDER_INVALID);
        assertError(() -> manualPosOrderService.create(userId, deliveryCashRequest(UUID.randomUUID(), california.id(), new BigDecimal("78.99"))),
                ManualPosOrderError.ORDER_CASH_DENOMINATION_INSUFFICIENT);

        ManualPosOrderResponse created = manualPosOrderService.create(userId,
                deliveryCashRequest(UUID.randomUUID(), california.id(), new BigDecimal("79.00")));

        assertThat(created.total()).isEqualByComparingTo("79.00");
        assertThat(created.cashDenomination()).isEqualByComparingTo("79.00");
        assertThat(created.deliveryAddress()).isEqualTo("Calle Principal 123");
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void deliveryTransferRejectsCashDenominationAndDeliveryCardRemainsInvalid() {
        MenuItemResponse california = item("California", "79.00");
        Long userId = insertUser("cashier-delivery-payment");
        List<PromotionQuoteLineRequest> lines = List.of(new PromotionQuoteLineRequest(
                "line", california.id(), 1, List.of(), List.of()));
        ManualPosOrderRequest transferWithCash = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.DELIVERY,
                OrderPaymentMethod.TRANSFER, "Calle Principal 123", null, new BigDecimal("100.00"), lines);
        ManualPosOrderRequest card = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.DELIVERY,
                OrderPaymentMethod.CARD, "Calle Principal 123", null, null, lines);

        assertError(() -> manualPosOrderService.create(userId, transferWithCash), ManualPosOrderError.ORDER_INVALID);
        assertError(() -> manualPosOrderService.create(userId, card), ManualPosOrderError.ORDER_INVALID);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
    }

    @Test
    void unavailableAndNonStandaloneRootsAreRejectedWithoutPersistingAnOrder() {
        MenuItemResponse unavailable = menuCatalogService.create(new CreateMenuItemRequest(
                "Agotado", null, "Rollos", new BigDecimal("79.00"), false, true, 0));
        MenuItemResponse nonStandalone = menuCatalogService.create(new CreateMenuItemRequest(
                "Topping", null, "Extras", new BigDecimal("15.00"), true, false, 0));
        Long userId = insertUser("cashier-root-validation");

        assertThatThrownBy(() -> manualPosOrderService.create(userId, request(UUID.randomUUID(), unavailable.id(), 1)))
                .isInstanceOf(com.sushimei.sushimei.backend.catalog.CatalogConfigurationException.class);
        assertThatThrownBy(() -> manualPosOrderService.create(userId, request(UUID.randomUUID(), nonStandalone.id(), 1)))
                .isInstanceOf(com.sushimei.sushimei.backend.catalog.CatalogConfigurationException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
    }

    @Test
    void persistsTheCurrentServerCatalogPriceAndKeepsSnapshotsAfterLiveChanges() {
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(california.id(),
                new CreateMenuSelectionGroupRequest("Topping", 0, 1, false, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, topping.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 0));
        MenuItemResponse repriced = menuCatalogService.update(california.id(), new UpdateMenuItemRequest(
                "California", null, "Rollos", new BigDecimal("91.00"), true, true, true, 0, california.version()));
        ManualPosOrderRequest request = configuredRequest(UUID.randomUUID(), repriced.id(), group.id(), topping.id());
        Long userId = insertUser("cashier-history");

        ManualPosOrderResponse created = manualPosOrderService.create(userId, request);
        menuCatalogService.update(repriced.id(), new UpdateMenuItemRequest("California cambiado", null, "Rollos", new BigDecimal("110.00"),
                true, true, true, 0, repriced.version()));
        catalogConfigurationService.archiveGroup(repriced.id(), group.id());
        ManualPosOrderResponse retry = manualPosOrderService.create(userId, request);

        assertThat(created.total()).isEqualByComparingTo("106.00");
        assertThat(retry.result()).isEqualTo(ManualOrderResult.ALREADY_CREATED);
        assertThat(retry.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("California");
            assertThat(line.catalogBaseUnitPrice()).isEqualByComparingTo("91.00");
            assertThat(line.configuration()).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.itemName()).isEqualTo("Olas");
                assertThat(snapshot.priceAdjustment()).isEqualByComparingTo("15.00");
            });
        });
    }

    @Test
    void persistsBackendGeneratedBogoRewardAsASeparateZeroValuedRewardLine() {
        TestClock.set(Instant.parse("2026-08-13T18:00:00Z"));
        MenuItemResponse california = item("California", "79.00");
        PromotionResponse promotion = promotionService.create(new CreatePromotionRequest("Jueves", true, 10,
                PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM, null, 1, 1, true, null, null,
                Set.of(4), List.of(new PromotionTargetRequest(PromotionTargetType.ITEM, california.id()))));

        Long userId = insertUser("cashier-c");
        ManualPosOrderRequest request = request(UUID.randomUUID(), california.id(), 1);
        ManualPosOrderResponse created = manualPosOrderService.create(userId, request);
        promotionService.archive(promotion.id());
        ManualPosOrderResponse retry = manualPosOrderService.create(userId, request);

        assertThat(created.total()).isEqualByComparingTo("79.00");
        assertThat(created.lines()).singleElement().satisfies(paid -> {
            assertThat(paid.lineKind()).isEqualTo(OrderLineKind.PAID);
            assertThat(paid.rewards()).singleElement().satisfies(reward -> {
                assertThat(reward.lineKind()).isEqualTo(OrderLineKind.PROMOTION_REWARD);
                assertThat(reward.sourceMenuItemId()).isEqualTo(california.id());
                assertThat(reward.finalUnitAmount()).isEqualByComparingTo("0.00");
                assertThat(reward.finalLineTotal()).isEqualByComparingTo("0.00");
                assertThat(reward.rewardOrdinal()).isEqualTo(1);
            });
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines where line_kind = 'PROMOTION_REWARD'", Integer.class))
                .isEqualTo(1);
        assertThat(retry.lines()).singleElement().satisfies(paid -> {
            assertThat(paid.promotion().id()).isEqualTo(promotion.id());
            assertThat(paid.rewards()).singleElement().satisfies(reward ->
                    assertThat(reward.promotion().name()).isEqualTo("Jueves"));
        });
    }

    @Test
    void fixedUnitPricePromotionChangesOnlyThePaidBaseAndKeepsConfigurationCharged() {
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(california.id(),
                new CreateMenuSelectionGroupRequest("Topping", 0, 1, false, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, topping.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 0));
        promotionService.create(new CreatePromotionRequest("Lunes", true, 10,
                PromotionBenefitType.FIXED_UNIT_PRICE, new BigDecimal("69.00"), null, null, null, null, null,
                Set.of(1), List.of(new PromotionTargetRequest(PromotionTargetType.ITEM, california.id()))));

        ManualPosOrderResponse created = manualPosOrderService.create(insertUser("cashier-fixed"),
                configuredRequest(UUID.randomUUID(), california.id(), group.id(), topping.id()));

        assertThat(created.total()).isEqualByComparingTo("84.00");
        assertThat(created.lines()).singleElement().satisfies(line -> {
            assertThat(line.chargedBaseUnitPrice()).isEqualByComparingTo("69.00");
            assertThat(line.configurationAdjustmentAmount()).isEqualByComparingTo("15.00");
            assertThat(line.finalLineTotal()).isEqualByComparingTo("84.00");
        });
    }

    @Test
    void bogoRewardKeepsItsOwnConfigurationAndChargedAdjustment() {
        TestClock.set(Instant.parse("2026-08-13T18:00:00Z"));
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(california.id(),
                new CreateMenuSelectionGroupRequest("Topping", 0, 1, false, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, topping.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 0));
        promotionService.create(new CreatePromotionRequest("Jueves", true, 10,
                PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM, null, 1, 1, true, null, null,
                Set.of(4), List.of(new PromotionTargetRequest(PromotionTargetType.ITEM, california.id()))));
        ManualPosOrderRequest request = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.CASH, null, "Ana", new BigDecimal("100.00"),
                List.of(new PromotionQuoteLineRequest("line", california.id(), 1, List.of(), List.of(
                        new PromotionRewardConfigurationRequest(1, List.of(new MenuQuoteGroupRequest(group.id(),
                                List.of(new MenuQuoteSelectionRequest(topping.id(), 1, List.of())))))))));

        ManualPosOrderResponse created = manualPosOrderService.create(insertUser("cashier-reward-config"), request);

        assertThat(created.total()).isEqualByComparingTo("94.00");
        assertThat(created.lines()).singleElement().satisfies(paid -> {
            assertThat(paid.configurationAdjustmentAmount()).isEqualByComparingTo("0.00");
            assertThat(paid.rewards()).singleElement().satisfies(reward -> {
                assertThat(reward.lineKind()).isEqualTo(OrderLineKind.PROMOTION_REWARD);
                assertThat(reward.configurationAdjustmentAmount()).isEqualByComparingTo("15.00");
                assertThat(reward.finalLineTotal()).isEqualByComparingTo("15.00");
                assertThat(reward.configuration()).singleElement().satisfies(snapshot ->
                        assertThat(snapshot.itemName()).isEqualTo("Olas"));
            });
        });
    }

    @Test
    void tiedPromotionConflictRollsBackEveryManualOrderSnapshot() {
        MenuItemResponse california = item("California", "79.00");
        promotionService.create(new CreatePromotionRequest("Uno", true, 10,
                PromotionBenefitType.FIXED_UNIT_PRICE, new BigDecimal("69.00"), null, null, null, null, null,
                Set.of(1), List.of(new PromotionTargetRequest(PromotionTargetType.ITEM, california.id()))));
        promotionService.create(new CreatePromotionRequest("Dos", true, 10,
                PromotionBenefitType.FIXED_UNIT_PRICE, new BigDecimal("68.00"), null, null, null, null, null,
                Set.of(1), List.of(new PromotionTargetRequest(PromotionTargetType.ITEM, california.id()))));

        assertThatThrownBy(() -> manualPosOrderService.create(insertUser("cashier-conflict"),
                request(UUID.randomUUID(), california.id(), 1)))
                .isInstanceOf(PromotionException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_line_selection_snapshots", Integer.class)).isZero();
    }

    @Test
    void invalidFulfillmentOrPaymentLeavesNoPartialOrder() {
        MenuItemResponse california = item("California", "79.00");
        ManualPosOrderRequest invalid = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.DELIVERY,
                OrderPaymentMethod.CARD, "Direccion valida", null, null,
                List.of(new PromotionQuoteLineRequest("line", california.id(), 1, List.of(), List.of())));

        assertThatThrownBy(() -> manualPosOrderService.create(insertUser("cashier-d"), invalid))
                .isInstanceOf(ManualPosOrderException.class)
                .extracting(exception -> ((ManualPosOrderException) exception).getError())
                .isEqualTo(ManualPosOrderError.ORDER_INVALID);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines", Integer.class)).isZero();
    }

    @Test
    void concurrentIdenticalRequestsPersistExactlyOneOrder() throws Exception {
        MenuItemResponse california = item("California", "79.00");
        Long userId = insertUser("cashier-concurrent");
        ManualPosOrderRequest request = request(UUID.randomUUID(), california.id(), 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ManualPosOrderResponse> first = executor.submit(() -> {
                start.await();
                return manualPosOrderService.create(userId, request);
            });
            Future<ManualPosOrderResponse> second = executor.submit(() -> {
                start.await();
                return manualPosOrderService.create(userId, request);
            });
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .extracting(ManualPosOrderResponse::result)
                    .containsExactlyInAnyOrder(ManualOrderResult.CREATED, ManualOrderResult.ALREADY_CREATED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void persistsServerResolvedConfigurationSnapshotRatherThanOnlyTheRequest() {
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(california.id(),
                new CreateMenuSelectionGroupRequest("Topping", 0, 1, false, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, topping.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 0));
        ManualPosOrderRequest request = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.CASH, null, "Ana", new BigDecimal("100.00"),
                List.of(new PromotionQuoteLineRequest("line", california.id(), 1,
                        List.of(new MenuQuoteGroupRequest(group.id(),
                                List.of(new MenuQuoteSelectionRequest(topping.id(), 1, List.of())))), List.of())));

        ManualPosOrderResponse created = manualPosOrderService.create(insertUser("cashier-e"), request);

        assertThat(created.total()).isEqualByComparingTo("94.00");
        assertThat(created.lines()).singleElement().satisfies(line -> {
            assertThat(line.configurationAdjustmentAmount()).isEqualByComparingTo("15.00");
            assertThat(line.configuration()).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.groupId()).isEqualTo(group.id());
                assertThat(snapshot.menuItemId()).isEqualTo(topping.id());
                assertThat(snapshot.catalogUnitPrice()).isEqualByComparingTo("15.00");
                assertThat(snapshot.priceAdjustment()).isEqualByComparingTo("15.00");
            });
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_line_selection_snapshots", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void persistsRecursiveConfigurationWithParentLinkedSnapshots() {
        MenuItemResponse root = item("Caja", "250.00");
        MenuItemResponse roll = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        MenuSelectionGroupResponse rootGroup = catalogConfigurationService.createGroup(root.id(),
                new CreateMenuSelectionGroupRequest("Rollo", 1, 1, false, 0));
        catalogConfigurationService.createRule(rootGroup.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, roll.id(), SelectionPricingPolicy.INCLUDED, null, null, 0));
        MenuSelectionGroupResponse rollGroup = catalogConfigurationService.createGroup(roll.id(),
                new CreateMenuSelectionGroupRequest("Topping", 0, 1, false, 0));
        catalogConfigurationService.createRule(rollGroup.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, topping.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 0));
        MenuQuoteGroupRequest toppingGroup = new MenuQuoteGroupRequest(rollGroup.id(),
                List.of(new MenuQuoteSelectionRequest(topping.id(), 1, List.of())));
        MenuQuoteSelectionRequest configuredRoll = new MenuQuoteSelectionRequest(roll.id(), 1, List.of(toppingGroup));
        ManualPosOrderRequest request = new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.CASH, null, "Ana", new BigDecimal("300.00"),
                List.of(new PromotionQuoteLineRequest("recursive", root.id(), 1,
                        List.of(new MenuQuoteGroupRequest(rootGroup.id(), List.of(configuredRoll))), List.of())));

        ManualPosOrderResponse created = manualPosOrderService.create(insertUser("cashier-recursive"), request);

        assertThat(created.total()).isEqualByComparingTo("265.00");
        assertThat(created.lines()).singleElement().satisfies(line -> {
            assertThat(line.configuration()).hasSize(2);
            ManualOrderSelectionSnapshotResponse selectedRoll = line.configuration().stream()
                    .filter(snapshot -> snapshot.menuItemId().equals(roll.id())).findFirst().orElseThrow();
            ManualOrderSelectionSnapshotResponse selectedTopping = line.configuration().stream()
                    .filter(snapshot -> snapshot.menuItemId().equals(topping.id())).findFirst().orElseThrow();
            assertThat(selectedRoll.parentSelectionSnapshotId()).isNull();
            assertThat(selectedTopping.parentSelectionSnapshotId()).isEqualTo(selectedRoll.id());
        });
    }

    private MenuItemResponse item(String name, String price) {
        return menuCatalogService.create(new CreateMenuItemRequest(name, null, "Rollos", new BigDecimal(price), true, true, 0));
    }

    private Long insertUser(String username) {
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, '{bcrypt}test', 'CASHIER', true, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """, username, username);
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
    }

    private ManualPosOrderRequest request(UUID requestId, Long menuItemId, int quantity) {
        return new ManualPosOrderRequest(requestId, OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH,
                null, "Ana", new BigDecimal("100.00"),
                List.of(new PromotionQuoteLineRequest("line", menuItemId, quantity, List.of(), List.of())));
    }

    private ManualPosOrderRequest deliveryCashRequest(UUID requestId, Long menuItemId, BigDecimal cashDenomination) {
        return new ManualPosOrderRequest(requestId, OrderFulfillmentType.DELIVERY, OrderPaymentMethod.CASH,
                "Calle Principal 123", null, cashDenomination,
                List.of(new PromotionQuoteLineRequest("delivery", menuItemId, 1, List.of(), List.of())));
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
                             ManualPosOrderError expected) {
        assertThatThrownBy(operation)
                .isInstanceOf(ManualPosOrderException.class)
                .extracting(exception -> ((ManualPosOrderException) exception).getError())
                .isEqualTo(expected);
    }

    private ManualPosOrderRequest configuredRequest(UUID requestId, Long menuItemId, Long groupId, Long toppingId) {
        return new ManualPosOrderRequest(requestId, OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH,
                null, "Ana", new BigDecimal("200.00"),
                List.of(new PromotionQuoteLineRequest("line", menuItemId, 1,
                        List.of(new MenuQuoteGroupRequest(groupId,
                                List.of(new MenuQuoteSelectionRequest(toppingId, 1, List.of())))), List.of())));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return new TestClock(); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }

    static final class TestClock extends Clock {
        private static final AtomicReference<Instant> NOW = new AtomicReference<>();
        private static final AtomicReference<Instant> AFTER_FIRST_READ = new AtomicReference<>();
        private static final java.util.concurrent.atomic.AtomicInteger READS = new java.util.concurrent.atomic.AtomicInteger();
        static void set(Instant instant) {
            NOW.set(instant);
            AFTER_FIRST_READ.set(null);
            READS.set(0);
        }
        static void setThenAdvance(Instant initial, Instant afterFirstRead) {
            NOW.set(initial);
            AFTER_FIRST_READ.set(afterFirstRead);
            READS.set(0);
        }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            return READS.getAndIncrement() == 0 || AFTER_FIRST_READ.get() == null ? NOW.get() : AFTER_FIRST_READ.get();
        }
    }
}
