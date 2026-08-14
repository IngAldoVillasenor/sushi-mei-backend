package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationService;
import com.sushimei.sushimei.backend.catalog.CatalogTagResponse;
import com.sushimei.sushimei.backend.catalog.CreateCatalogTagRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuSelectionGroupRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuSelectionRuleRequest;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
import com.sushimei.sushimei.backend.catalog.MenuItemResponse;
import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest;
import com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionRequest;
import com.sushimei.sushimei.backend.catalog.MenuSelectionGroupResponse;
import com.sushimei.sushimei.backend.catalog.ReplaceMenuItemTagsRequest;
import com.sushimei.sushimei.backend.catalog.SelectionPricingPolicy;
import com.sushimei.sushimei.backend.catalog.SelectionRuleTargetType;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, TemporalPromotionQuoteServiceIntegrationTest.TestInfrastructureConfiguration.class})
class TemporalPromotionQuoteServiceIntegrationTest {

    private static final Instant MONDAY = Instant.parse("2026-08-10T18:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2026-08-13T18:00:00Z");

    @Autowired private MenuCatalogService menuCatalogService;
    @Autowired private CatalogConfigurationService catalogConfigurationService;
    @Autowired private PromotionService promotionService;
    @Autowired private TemporalPromotionQuoteService temporalPromotionQuoteService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.promotion_targets");
        jdbcTemplate.update("delete from public.promotion_weekdays");
        jdbcTemplate.update("delete from public.promotions");
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
        entityManager.clear();
        TestClock.set(MONDAY);
    }

    @Test
    void mondayFixedBasePriceKeepsConfigurationAdjustmentsExact() {
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        CatalogTagResponse classic = tag("ROLL_CLASSIC");
        california = assign(california, classic);
        MenuSelectionGroupResponse group = optionalGroup(california, "Topping");
        includedRule(group, topping);
        fixedPromotion("Lunes clasicos", 10, Set.of(1), targetTag(classic), "69.00");

        PromotionQuoteResponse one = quote(line("one", california.id(), 1, List.of(group(group, topping)), List.of()));
        assertThat(one.catalogBaseSubtotal()).isEqualByComparingTo("79.00");
        assertThat(one.promotionAdjustmentTotal()).isEqualByComparingTo("-10.00");
        assertThat(one.total()).isEqualByComparingTo("84.00");
        assertThat(one.lines().get(0).chargedBaseUnitPrice()).isEqualByComparingTo("69.00");

        PromotionQuoteResponse three = quote(line("three", california.id(), 3, List.of(), List.of()));
        assertThat(three.catalogBaseSubtotal()).isEqualByComparingTo("237.00");
        assertThat(three.promotionAdjustmentTotal()).isEqualByComparingTo("-30.00");
        assertThat(three.total()).isEqualByComparingTo("207.00");
    }

    @Test
    void thursdayBuyOneGetOneSameItemCreatesIndependentlyConfiguredRewards() {
        TestClock.set(THURSDAY);
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse topping = item("Olas", "15.00");
        CatalogTagResponse classic = tag("ROLL_CLASSIC");
        california = assign(california, classic);
        MenuSelectionGroupResponse group = optionalGroup(california, "Topping");
        includedRule(group, topping);
        bogoPromotion("Jueves clasicos", 10, Set.of(4), targetTag(classic), 1, 1, true);

        PromotionQuoteResponse single = quote(line("single", california.id(), 1, List.of(), List.of()));
        assertThat(single.lines().get(0).rewards()).hasSize(1);
        assertThat(single.lines().get(0).rewards().get(0).menuItemId()).isEqualTo(california.id());
        assertThat(single.lines().get(0).rewards().get(0).chargedBaseUnitPrice()).isEqualByComparingTo("0.00");
        assertThat(single.total()).isEqualByComparingTo("79.00");

        PromotionQuoteResponse three = quote(line("three", california.id(), 3, List.of(), List.of()));
        assertThat(three.lines().get(0).rewards()).hasSize(3);
        assertThat(three.catalogBaseSubtotal()).isEqualByComparingTo("237.00");
        assertThat(three.promotionAdjustmentTotal()).isEqualByComparingTo("0.00");
        assertThat(three.total()).isEqualByComparingTo("237.00");

        PromotionQuoteResponse sourceTopping = quote(line("source", california.id(), 1, List.of(group(group, topping)), List.of()));
        assertThat(sourceTopping.total()).isEqualByComparingTo("94.00");
        PromotionQuoteResponse rewardTopping = quote(line("reward", california.id(), 1, List.of(),
                List.of(reward(1, group(group, topping)))));
        assertThat(rewardTopping.catalogBaseSubtotal()).isEqualByComparingTo("79.00");
        assertThat(rewardTopping.configurationAdjustmentTotal()).isEqualByComparingTo("15.00");
        assertThat(rewardTopping.promotionAdjustmentTotal()).isEqualByComparingTo("0.00");
        assertThat(rewardTopping.total()).isEqualByComparingTo("94.00");
        assertAccounting(rewardTopping);
        PromotionQuoteResponse bothToppings = quote(line("both", california.id(), 1, List.of(group(group, topping)),
                List.of(reward(1, group(group, topping)))));
        assertThat(bothToppings.catalogBaseSubtotal()).isEqualByComparingTo("79.00");
        assertThat(bothToppings.configurationAdjustmentTotal()).isEqualByComparingTo("30.00");
        assertThat(bothToppings.promotionAdjustmentTotal()).isEqualByComparingTo("0.00");
        assertThat(bothToppings.total()).isEqualByComparingTo("109.00");
        assertAccounting(bothToppings);
    }

    @Test
    void rootOnlyTargetResolutionHonorsDatesTagsItemsPrioritiesAndTies() {
        TestClock.set(THURSDAY);
        MenuItemResponse california = item("California", "79.00");
        MenuItemResponse philadelphia = item("Philadelphia", "85.00");
        MenuItemResponse box = item("Sushi Box", "250.00");
        CatalogTagResponse classic = tag("ROLL_CLASSIC");
        california = assign(california, classic);
        final Long californiaId = california.id();
        philadelphia = assign(philadelphia, classic);
        MenuSelectionGroupResponse boxGroup = optionalGroup(box, "Rollo");
        includedRule(boxGroup, california);
        bogoPromotion("Tag Thursday", 10, Set.of(4), targetTag(classic), 1, 1, true);

        PromotionQuoteResponse bothItems = quote(
                line("cal", california.id(), 1, List.of(), List.of()),
                line("phil", philadelphia.id(), 1, List.of(), List.of()));
        assertThat(bothItems.lines()).allSatisfy(line -> assertThat(line.rewards()).hasSize(1));
        assertThat(bothItems.lines().get(0).rewards().get(0).menuItemId()).isEqualTo(california.id());
        assertThat(bothItems.lines().get(1).rewards().get(0).menuItemId()).isEqualTo(philadelphia.id());
        assertThat(quote(line("box", box.id(), 1, List.of(group(boxGroup, california)), List.of())).lines().get(0).rewards())
                .isEmpty();

        PromotionResponse highItem = fixedPromotion("Item override", 20, Set.of(4), targetItem(california), "69.00");
        assertThat(quote(line("priority", california.id(), 1, List.of(), List.of())).total()).isEqualByComparingTo("69.00");
        promotionService.archive(highItem.id());
        assertThatThrownBy(() -> fixedPromotion("Tie", 10, Set.of(4), targetItem(california), "70.00"))
                .isInstanceOf(PromotionException.class)
                .extracting(exception -> ((PromotionException) exception).getError())
                .isEqualTo(PromotionError.PROMOTION_SCHEDULE_CONFLICT);
        jdbcTemplate.update("""
                insert into public.promotions (name, active, priority, benefit_type, fixed_unit_price_amount,
                    created_at, updated_at, version)
                values ('Corrupted tie', true, 10, 'FIXED_UNIT_PRICE', 70.00, current_timestamp, current_timestamp, 0)
                """);
        Long tieId = jdbcTemplate.queryForObject("select id from public.promotions where name = 'Corrupted tie'", Long.class);
        jdbcTemplate.update("insert into public.promotion_weekdays (promotion_id, iso_day_of_week) values (?, 4)", tieId);
        jdbcTemplate.update("insert into public.promotion_targets (promotion_id, target_menu_item_id) values (?, ?)",
                tieId, california.id());
        assertThatThrownBy(() -> quote(line("tie", californiaId, 1, List.of(), List.of())))
                .isInstanceOf(PromotionException.class)
                .extracting(exception -> ((PromotionException) exception).getError())
                .isEqualTo(PromotionError.PROMOTION_CONFIGURATION_CONFLICT);
        jdbcTemplate.update("update public.promotions set active = false where id = ?", tieId);
        TestClock.set(MONDAY);
        assertThat(quote(line("weekday", california.id(), 1, List.of(), List.of())).lines().get(0).rewards()).isEmpty();
        PromotionResponse archivedMonday = fixedPromotion("Archived Monday", 30, Set.of(1), targetItem(california), "69.00");
        promotionService.archive(archivedMonday.id());
        assertThat(quote(line("inactive", california.id(), 1, List.of(), List.of())).total()).isEqualByComparingTo("79.00");
        promotionService.create(new CreatePromotionRequest("Future Monday", true, 30, PromotionBenefitType.FIXED_UNIT_PRICE,
                new BigDecimal("69.00"), null, null, null, LocalDate.of(2026, 8, 11), null,
                Set.of(1), List.of(targetItem(california))));
        promotionService.create(new CreatePromotionRequest("Expired Monday", true, 31, PromotionBenefitType.FIXED_UNIT_PRICE,
                new BigDecimal("69.00"), null, null, null, null, LocalDate.of(2026, 8, 9),
                Set.of(1), List.of(targetItem(california))));
        assertThat(quote(line("dates", california.id(), 1, List.of(), List.of())).total()).isEqualByComparingTo("79.00");
    }

    @Test
    void invalidRewardCorrelationAndInactiveTagEligibilityAreRejectedDeterministically() {
        TestClock.set(THURSDAY);
        MenuItemResponse california = item("California", "79.00");
        CatalogTagResponse classic = tag("ROLL_CLASSIC");
        california = assign(california, classic);
        final Long californiaId = california.id();
        bogoPromotion("Thursday", 10, Set.of(4), targetTag(classic), 1, 1, true);

        assertThatThrownBy(() -> quote(line("one", californiaId, 1, List.of(), List.of(reward(2)))))
                .isInstanceOf(PromotionException.class)
                .extracting(exception -> ((PromotionException) exception).getError()).isEqualTo(PromotionError.PROMOTION_REWARD_INVALID);
        assertThatThrownBy(() -> quote(line("one", californiaId, 1, List.of(), List.of(reward(1), reward(1)))))
                .isInstanceOf(PromotionException.class)
                .extracting(exception -> ((PromotionException) exception).getError()).isEqualTo(PromotionError.PROMOTION_REWARD_INVALID);
        assertThatThrownBy(() -> quote(line("duplicate", californiaId, 1, List.of(), List.of()),
                line("duplicate", californiaId, 1, List.of(), List.of())))
                .isInstanceOf(PromotionException.class)
                .extracting(exception -> ((PromotionException) exception).getError()).isEqualTo(PromotionError.PROMOTION_QUOTE_INVALID);

        catalogConfigurationService.archiveTag(classic.id());
        assertThat(quote(line("inactive-tag", california.id(), 1, List.of(), List.of())).lines().get(0).rewards()).isEmpty();
        assertThat(PromotionRewardConfigurationRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("rewardOrdinal", "groups");
    }

    @Test
    void businessDateUsesAmericaMexicoCityRatherThanTheUtcCalendarDay() {
        MenuItemResponse california = item("California", "79.00");
        bogoPromotion("Thursday", 10, Set.of(4), targetItem(california), 1, 1, true);

        TestClock.set(Instant.parse("2026-08-13T05:59:00Z"));
        assertThat(quote(line("before-local-thursday", california.id(), 1, List.of(), List.of()))
                .lines().get(0).rewards()).isEmpty();

        TestClock.set(Instant.parse("2026-08-13T06:01:00Z"));
        assertThat(quote(line("after-local-thursday", california.id(), 1, List.of(), List.of()))
                .lines().get(0).rewards()).hasSize(1);
    }

    @Test
    void genericBuyGetRewardCountsRespectRepeatConfiguration() {
        TestClock.set(THURSDAY);
        MenuItemResponse california = item("California", "79.00");
        PromotionResponse repeating = bogoPromotion("Buy two get one repeating", 10, Set.of(4), targetItem(california),
                2, 1, true);
        assertThat(quote(line("repeat", california.id(), 5, List.of(), List.of())).lines().get(0).rewards()).hasSize(2);

        promotionService.archive(repeating.id());
        bogoPromotion("Buy two get one once", 10, Set.of(4), targetItem(california), 2, 1, false);
        assertThat(quote(line("once", california.id(), 5, List.of(), List.of())).lines().get(0).rewards()).hasSize(1);
    }

    private void assertAccounting(PromotionQuoteResponse response) {
        assertThat(response.catalogBaseSubtotal()
                .add(response.configurationAdjustmentTotal())
                .add(response.promotionAdjustmentTotal()))
                .isEqualByComparingTo(response.total());
    }
    private MenuItemResponse item(String name, String price) {
        return menuCatalogService.create(new CreateMenuItemRequest(name, null, "Rollos", new BigDecimal(price), true, true, 0));
    }
    private CatalogTagResponse tag(String code) { return catalogConfigurationService.createTag(new CreateCatalogTagRequest(code, code, 0)); }
    private MenuItemResponse assign(MenuItemResponse item, CatalogTagResponse tag) {
        return catalogConfigurationService.replaceItemTags(item.id(), new ReplaceMenuItemTagsRequest(item.version(), List.of(tag.id())));
    }
    private MenuSelectionGroupResponse optionalGroup(MenuItemResponse item, String name) {
        return catalogConfigurationService.createGroup(item.id(), new CreateMenuSelectionGroupRequest(name, 0, 1, false, 0));
    }
    private void includedRule(MenuSelectionGroupResponse group, MenuItemResponse item) {
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, item.id(), SelectionPricingPolicy.FIXED_SURCHARGE, null, new BigDecimal("15.00"), 0));
    }
    private PromotionResponse fixedPromotion(String name, int priority, Set<Integer> weekdays, PromotionTargetRequest target, String price) {
        return promotionService.create(new CreatePromotionRequest(name, true, priority, PromotionBenefitType.FIXED_UNIT_PRICE,
                new BigDecimal(price), null, null, null, null, null, weekdays, List.of(target)));
    }
    private PromotionResponse bogoPromotion(String name, int priority, Set<Integer> weekdays, PromotionTargetRequest target,
                                            int buy, int reward, boolean repeat) {
        return promotionService.create(new CreatePromotionRequest(name, true, priority, PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM,
                null, buy, reward, repeat, null, null, weekdays, List.of(target)));
    }
    private PromotionTargetRequest targetTag(CatalogTagResponse tag) { return new PromotionTargetRequest(PromotionTargetType.TAG, tag.id()); }
    private PromotionTargetRequest targetItem(MenuItemResponse item) { return new PromotionTargetRequest(PromotionTargetType.ITEM, item.id()); }
    private PromotionQuoteResponse quote(PromotionQuoteLineRequest... lines) { return temporalPromotionQuoteService.quote(new PromotionQuoteRequest(List.of(lines))); }
    private PromotionQuoteLineRequest line(String key, Long itemId, int quantity, List<MenuQuoteGroupRequest> groups,
                                           List<PromotionRewardConfigurationRequest> rewards) {
        return new PromotionQuoteLineRequest(key, itemId, quantity, groups, rewards);
    }
    private MenuQuoteGroupRequest group(MenuSelectionGroupResponse group, MenuItemResponse selection) {
        return new MenuQuoteGroupRequest(group.id(), List.of(new MenuQuoteSelectionRequest(selection.id(), 1, List.of())));
    }
    private PromotionRewardConfigurationRequest reward(int ordinal, MenuQuoteGroupRequest... groups) {
        return new PromotionRewardConfigurationRequest(ordinal, List.of(groups));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return new TestClock(); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }

    static final class TestClock extends Clock {
        private static final AtomicReference<Instant> NOW = new AtomicReference<>(MONDAY);
        static void set(Instant instant) { NOW.set(instant); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW.get(); }
    }
}
