package com.sushimei.sushimei.backend.catalog;

import com.sushimei.sushimei.backend.promotion.CreatePromotionRequest;
import com.sushimei.sushimei.backend.promotion.PromotionBenefitType;
import com.sushimei.sushimei.backend.promotion.PromotionResponse;
import com.sushimei.sushimei.backend.promotion.PromotionService;
import com.sushimei.sushimei.backend.promotion.PromotionTargetRequest;
import com.sushimei.sushimei.backend.promotion.PromotionTargetType;
import com.sushimei.sushimei.backend.promotion.UpdatePromotionRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activates the reviewed temporal promotions after the authoritative catalog
 * runner has materialized the catalog tags required by their targets.
 */
@Component
@Order(200)
class AuthoritativePromotionRulesBootstrap implements ApplicationRunner {

    private final AuthoritativePromotionRulesService rulesService;

    AuthoritativePromotionRulesBootstrap(AuthoritativePromotionRulesService rulesService) {
        this.rulesService = rulesService;
    }

    @Override
    public void run(ApplicationArguments args) {
        rulesService.synchronize();
    }
}

@Service
class AuthoritativePromotionRulesService {

    static final String RULE_SET_ID = "PHASE_6G_P0_A_AUTHORITATIVE_TEMPORAL_PROMOTIONS";
    static final String CLASSIC_ROLL_TAG_RULE_SET_ID = "PHASE_6G_P0_C_CLASSIC_ROLL_TAG_PROMOTIONS";

    private static final String LEGACY_MONDAY_ITEM_NAME = "Lunes $69";
    private static final String LEGACY_MONDAY_ITEM_CATEGORY = "Promociones";
    private static final String CLASSIC_ROLL_TAG_CODE = "ROLLO_CLASICO";
    private static final List<String> AUTHORITATIVE_PROMOTION_NAMES = List.of("Lunes $69", "Jueves 2x1");
    private static final int PRIORITY = 100;

    private final MenuCatalogRepository menuItems;
    private final CatalogTagRepository catalogTags;
    private final PromotionService promotionService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    AuthoritativePromotionRulesService(MenuCatalogRepository menuItems,
                                       CatalogTagRepository catalogTags,
                                       PromotionService promotionService,
                                       JdbcTemplate jdbcTemplate,
                                       Clock clock) {
        this.menuItems = Objects.requireNonNull(menuItems, "menuItems must not be null");
        this.catalogTags = Objects.requireNonNull(catalogTags, "catalogTags must not be null");
        this.promotionService = Objects.requireNonNull(promotionService, "promotionService must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public void synchronize() {
        if (!isRuleSetAppliedWithLock(RULE_SET_ID)) {
            Instant now = clock.instant();
            archiveLegacyMondayItem(now);
            createMondayPromotion();
            createThursdayPromotion();
            menuItems.flush();
            markRuleSetApplied(RULE_SET_ID, now);
        }

        if (!isRuleSetAppliedWithLock(CLASSIC_ROLL_TAG_RULE_SET_ID)) {
            normalizeClassicRollPromotionTargets();
            markRuleSetApplied(CLASSIC_ROLL_TAG_RULE_SET_ID, clock.instant());
        }
    }

    private void markRuleSetApplied(String ruleSetId, Instant now) {
        OffsetDateTime databaseNow = now.atOffset(ZoneOffset.UTC);
        int marked = jdbcTemplate.update("""
                update public.promotion_bootstrap_rule_sets
                set applied_at = ?
                where rule_set_id = ? and applied_at is null
                """, databaseNow, ruleSetId);
        if (marked != 1) {
            throw new IllegalStateException("Authoritative promotion rule set could not be marked as applied");
        }
    }

    private boolean isRuleSetAppliedWithLock(String ruleSetId) {
        try {
            Boolean applied = jdbcTemplate.queryForObject("""
                    select applied_at is not null
                    from public.promotion_bootstrap_rule_sets
                    where rule_set_id = ?
                    for update
                    """, Boolean.class, ruleSetId);
            if (applied == null) {
                throw new IllegalStateException("Authoritative promotion rule-set marker is invalid");
            }
            return applied;
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Missing authoritative promotion rule-set marker", exception);
        }
    }

    private void archiveLegacyMondayItem(Instant now) {
        MenuItem legacyMondayItem = menuItems.findById(65L).orElseThrow(() ->
                new IllegalStateException("Missing legacy Lunes $69 menu item"));
        if (!LEGACY_MONDAY_ITEM_NAME.equals(legacyMondayItem.getName())
                || !LEGACY_MONDAY_ITEM_CATEGORY.equals(legacyMondayItem.getCategory())) {
            throw new IllegalStateException("Legacy Monday promotion menu item has an unexpected identity");
        }
        legacyMondayItem.archiveAsDiscontinued(now);
    }

    private void createMondayPromotion() {
        promotionService.create(new CreatePromotionRequest(
                "Lunes $69",
                true,
                PRIORITY,
                PromotionBenefitType.FIXED_UNIT_PRICE,
                new BigDecimal("69.00"),
                null,
                null,
                null,
                null,
                null,
                Set.of(1),
                classicRollTagTarget()));
    }

    private void createThursdayPromotion() {
        promotionService.create(new CreatePromotionRequest(
                "Jueves 2x1",
                true,
                PRIORITY,
                PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM,
                null,
                1,
                1,
                true,
                null,
                null,
                Set.of(4),
                classicRollTagTarget()));
    }

    private void normalizeClassicRollPromotionTargets() {
        List<PromotionTargetRequest> target = classicRollTagTarget();
        Long targetId = target.get(0).targetId();
        List<PromotionResponse> promotions = promotionService.list(true);
        for (String name : AUTHORITATIVE_PROMOTION_NAMES) {
            PromotionResponse promotion = promotions.stream()
                    .filter(candidate -> name.equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing authoritative promotion " + name));
            boolean alreadyTargetsClassicRollTag = promotion.targets().size() == 1
                    && promotion.targets().get(0).targetType() == PromotionTargetType.TAG
                    && targetId.equals(promotion.targets().get(0).targetId());
            if (!alreadyTargetsClassicRollTag) {
                promotionService.update(promotion.id(), new UpdatePromotionRequest(
                        promotion.name(), promotion.active(), promotion.priority(), promotion.benefitType(),
                        promotion.fixedUnitPrice(), promotion.buyQuantity(), promotion.rewardQuantity(), promotion.repeat(),
                        promotion.validFrom(), promotion.validUntil(), promotion.daysOfWeek(), target, promotion.version()));
            }
        }
    }

    private List<PromotionTargetRequest> classicRollTagTarget() {
        CatalogTag classicRollTag = catalogTags.findByCode(CLASSIC_ROLL_TAG_CODE)
                .filter(CatalogTag::isActive)
                .orElseThrow(() -> new IllegalStateException("Missing active ROLLO_CLASICO catalog tag"));
        return List.of(new PromotionTargetRequest(PromotionTargetType.TAG, classicRollTag.getId()));
    }
}
