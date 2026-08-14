package com.sushimei.sushimei.backend.catalog;

import com.sushimei.sushimei.backend.promotion.CreatePromotionRequest;
import com.sushimei.sushimei.backend.promotion.PromotionBenefitType;
import com.sushimei.sushimei.backend.promotion.PromotionService;
import com.sushimei.sushimei.backend.promotion.PromotionTargetRequest;
import com.sushimei.sushimei.backend.promotion.PromotionTargetType;
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
 * runner has materialized the menu items required by their ITEM targets.
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

    private static final String LEGACY_MONDAY_ITEM_NAME = "Lunes $69";
    private static final String LEGACY_MONDAY_ITEM_CATEGORY = "Promociones";
    private static final List<Long> ELIGIBLE_CLASSIC_ROLL_IDS = List.of(18L, 24L, 49L, 80L, 107L);
    private static final int PRIORITY = 100;

    private final MenuCatalogRepository menuItems;
    private final PromotionService promotionService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    AuthoritativePromotionRulesService(MenuCatalogRepository menuItems,
                                       PromotionService promotionService,
                                       JdbcTemplate jdbcTemplate,
                                       Clock clock) {
        this.menuItems = Objects.requireNonNull(menuItems, "menuItems must not be null");
        this.promotionService = Objects.requireNonNull(promotionService, "promotionService must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public void synchronize() {
        if (isRuleSetAppliedWithLock()) {
            return;
        }

        Instant now = clock.instant();
        archiveLegacyMondayItem(now);
        createMondayPromotion();
        createThursdayPromotion();
        menuItems.flush();

        OffsetDateTime databaseNow = now.atOffset(ZoneOffset.UTC);
        int marked = jdbcTemplate.update("""
                update public.promotion_bootstrap_rule_sets
                set applied_at = ?
                where rule_set_id = ? and applied_at is null
                """, databaseNow, RULE_SET_ID);
        if (marked != 1) {
            throw new IllegalStateException("Authoritative promotion rule set could not be marked as applied");
        }
    }

    private boolean isRuleSetAppliedWithLock() {
        try {
            Boolean applied = jdbcTemplate.queryForObject("""
                    select applied_at is not null
                    from public.promotion_bootstrap_rule_sets
                    where rule_set_id = ?
                    for update
                    """, Boolean.class, RULE_SET_ID);
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
                itemTargets()));
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
                itemTargets()));
    }

    private List<PromotionTargetRequest> itemTargets() {
        return ELIGIBLE_CLASSIC_ROLL_IDS.stream()
                .map(id -> new PromotionTargetRequest(PromotionTargetType.ITEM, id))
                .toList();
    }
}
