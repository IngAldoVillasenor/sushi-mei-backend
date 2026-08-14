package com.sushimei.sushimei.backend.catalog;

import com.sushimei.sushimei.backend.promotion.PromotionBenefitType;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteResponse;
import com.sushimei.sushimei.backend.promotion.PromotionResponse;
import com.sushimei.sushimei.backend.promotion.PromotionService;
import com.sushimei.sushimei.backend.promotion.PromotionTargetResponse;
import com.sushimei.sushimei.backend.promotion.PromotionTargetType;
import com.sushimei.sushimei.backend.promotion.TemporalPromotionQuoteService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        AuthoritativePromotionRulesIntegrationTest.TestInfrastructureConfiguration.class})
class AuthoritativePromotionRulesIntegrationTest {

    private static final Instant MONDAY = Instant.parse("2026-08-10T18:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-08-11T18:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2026-08-13T18:00:00Z");
    private static final Set<Long> ELIGIBLE_CLASSIC_ROLL_IDS = Set.of(18L, 24L, 49L, 80L, 107L);

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private TemporalPromotionQuoteService promotionQuoteService;

    @Autowired
    private MenuCatalogService menuCatalogService;

    @Autowired
    private AuthoritativePromotionRulesService rulesService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DirtiesContext
    void startupCreatesTheTwoReviewedPromotionsArchivesTheLegacySkuAndDoesNotRewriteThem() {
        Map<String, PromotionResponse> promotions = promotionService.list(false).stream()
                .collect(java.util.stream.Collectors.toMap(PromotionResponse::name, promotion -> promotion));

        assertThat(promotions).hasSize(2);
        assertMondayPromotion(promotions.get("Lunes $69"));
        assertThursdayPromotion(promotions.get("Jueves 2x1"));

        MenuItemResponse legacyItem = menuCatalogService.get(65L);
        assertThat(legacyItem.name()).isEqualTo("Lunes $69");
        assertThat(legacyItem.active()).isFalse();
        assertThat(legacyItem.available()).isFalse();
        assertThat(legacyItem.standaloneOrderable()).isFalse();

        rulesService.synchronize();

        assertThat(promotionService.list(true)).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotion_targets", Integer.class)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.promotion_bootstrap_rule_sets
                where rule_set_id = ? and applied_at is not null
                """, Integer.class, AuthoritativePromotionRulesService.RULE_SET_ID)).isEqualTo(1);

        promotionService.archive(promotions.get("Lunes $69").id());
        rulesService.synchronize();

        assertThat(promotionService.get(promotions.get("Lunes $69").id()).active()).isFalse();
    }

    @Test
    void mondayAndThursdayQuotesUseOnlyTheReviewedRootItemPromotions() {
        TestClock.set(MONDAY);
        PromotionQuoteResponse monday = quote(24L, 1);
        assertThat(monday.catalogBaseSubtotal()).isEqualByComparingTo("79.00");
        assertThat(monday.configurationAdjustmentTotal()).isEqualByComparingTo("0.00");
        assertThat(monday.promotionAdjustmentTotal()).isEqualByComparingTo("-10.00");
        assertThat(monday.total()).isEqualByComparingTo("69.00");
        assertThat(monday.lines().get(0).chargedBaseUnitPrice()).isEqualByComparingTo("69.00");
        assertThat(monday.lines().get(0).appliedPromotion().name()).isEqualTo("Lunes $69");

        TestClock.set(TUESDAY);
        PromotionQuoteResponse outsideMonday = quote(24L, 1);
        assertThat(outsideMonday.lines().get(0).appliedPromotion()).isNull();
        assertThat(outsideMonday.total()).isEqualByComparingTo("79.00");

        TestClock.set(MONDAY);
        PromotionQuoteResponse nonEligibleMondayRoll = quote(14L, 1);
        assertThat(nonEligibleMondayRoll.lines().get(0).appliedPromotion()).isNull();

        TestClock.set(THURSDAY);
        PromotionQuoteResponse oneThursdayRoll = quote(24L, 1);
        assertThat(oneThursdayRoll.lines().get(0).rewards()).hasSize(1);
        assertThat(oneThursdayRoll.lines().get(0).rewards().get(0).menuItemId()).isEqualTo(24L);
        assertThat(oneThursdayRoll.lines().get(0).rewards().get(0).chargedBaseUnitPrice())
                .isEqualByComparingTo("0.00");
        assertThat(oneThursdayRoll.total()).isEqualByComparingTo("79.00");

        PromotionQuoteResponse twoThursdayRolls = quote(24L, 2);
        assertThat(twoThursdayRolls.lines().get(0).rewards()).hasSize(2);

        PromotionQuoteResponse nonEligibleThursdayRoll = quote(14L, 1);
        assertThat(nonEligibleThursdayRoll.lines().get(0).appliedPromotion()).isNull();
        assertThat(nonEligibleThursdayRoll.lines().get(0).rewards()).isEmpty();

        TestClock.set(TUESDAY);
        assertThat(quote(24L, 1).lines().get(0).rewards()).isEmpty();
    }

    private void assertMondayPromotion(PromotionResponse promotion) {
        assertThat(promotion).isNotNull();
        assertThat(promotion.active()).isTrue();
        assertThat(promotion.benefitType()).isEqualTo(PromotionBenefitType.FIXED_UNIT_PRICE);
        assertThat(promotion.fixedUnitPrice()).isEqualByComparingTo("69.00");
        assertThat(promotion.buyQuantity()).isNull();
        assertThat(promotion.rewardQuantity()).isNull();
        assertThat(promotion.repeat()).isNull();
        assertThat(promotion.validFrom()).isNull();
        assertThat(promotion.validUntil()).isNull();
        assertThat(promotion.daysOfWeek()).containsExactly(1);
        assertItemTargets(promotion);
    }

    private void assertThursdayPromotion(PromotionResponse promotion) {
        assertThat(promotion).isNotNull();
        assertThat(promotion.active()).isTrue();
        assertThat(promotion.benefitType()).isEqualTo(PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM);
        assertThat(promotion.fixedUnitPrice()).isNull();
        assertThat(promotion.buyQuantity()).isEqualTo(1);
        assertThat(promotion.rewardQuantity()).isEqualTo(1);
        assertThat(promotion.repeat()).isTrue();
        assertThat(promotion.validFrom()).isNull();
        assertThat(promotion.validUntil()).isNull();
        assertThat(promotion.daysOfWeek()).containsExactly(4);
        assertItemTargets(promotion);
    }

    private void assertItemTargets(PromotionResponse promotion) {
        assertThat(promotion.targets()).extracting(PromotionTargetResponse::targetType)
                .containsOnly(PromotionTargetType.ITEM);
        assertThat(promotion.targets()).extracting(PromotionTargetResponse::targetId)
                .containsExactlyInAnyOrderElementsOf(ELIGIBLE_CLASSIC_ROLL_IDS);
        assertThat(promotion.targets()).extracting(PromotionTargetResponse::targetId)
                .doesNotContain(53L, 74L, 108L);
    }

    private PromotionQuoteResponse quote(long menuItemId, int quantity) {
        return promotionQuoteService.quote(new PromotionQuoteRequest(List.of(
                new PromotionQuoteLineRequest("line-" + menuItemId + '-' + quantity, menuItemId, quantity,
                        List.of(), List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return new TestClock();
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

    static final class TestClock extends Clock {

        private static final AtomicReference<Instant> NOW = new AtomicReference<>(MONDAY);

        static void set(Instant instant) {
            NOW.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return NOW.get();
        }
    }
}
