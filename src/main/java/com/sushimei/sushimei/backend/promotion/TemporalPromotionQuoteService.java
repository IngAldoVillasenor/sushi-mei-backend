package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationService;
import com.sushimei.sushimei.backend.catalog.CatalogTag;
import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import com.sushimei.sushimei.backend.catalog.MenuItemQuoteRequest;
import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TemporalPromotionQuoteService {

    private final PromotionRepository promotionRepository;
    private final MenuCatalogRepository menuCatalogRepository;
    private final CatalogConfigurationService catalogConfigurationService;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;
    private final ZoneId businessZone;

    public TemporalPromotionQuoteService(PromotionRepository promotionRepository,
                                         MenuCatalogRepository menuCatalogRepository,
                                         CatalogConfigurationService catalogConfigurationService,
                                         CheckoutMoney checkoutMoney,
                                         Clock clock,
                                         @Value("${sushimei.business-zone:America/Mexico_City}") String businessZone) {
        this.promotionRepository = Objects.requireNonNull(promotionRepository, "promotionRepository must not be null");
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository, "menuCatalogRepository must not be null");
        this.catalogConfigurationService = Objects.requireNonNull(catalogConfigurationService,
                "catalogConfigurationService must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.businessZone = ZoneId.of(Objects.requireNonNull(businessZone, "businessZone must not be null"));
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> listApplicable() {
        LocalDate businessDate = clock.instant().atZone(businessZone).toLocalDate();
        return applicablePromotions(businessDate).stream().map(PromotionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PromotionQuoteResponse quote(PromotionQuoteRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw invalidQuote();
        }
        Instant quotedAt = clock.instant();
        LocalDate businessDate = quotedAt.atZone(businessZone).toLocalDate();
        List<Promotion> applicablePromotions = applicablePromotions(businessDate);
        Set<String> lineKeys = new HashSet<>();
        List<PromotionQuoteLineResponse> lines = new ArrayList<>();
        BigDecimal catalogBaseSubtotal = zero();
        BigDecimal configurationAdjustmentTotal = zero();
        BigDecimal promotionAdjustmentTotal = zero();
        BigDecimal total = zero();

        for (PromotionQuoteLineRequest line : request.lines()) {
            if (line == null || line.lineKey() == null || line.lineKey().trim().isEmpty() || line.menuItemId() == null
                    || line.menuItemId() <= 0 || line.quantity() == null || line.quantity() <= 0
                    || !lineKeys.add(line.lineKey().trim())) {
                throw invalidQuote();
            }
            QuotedLine quoted = quoteLine(line, applicablePromotions);
            lines.add(quoted.response());
            catalogBaseSubtotal = addNonNegative(catalogBaseSubtotal, quoted.catalogBaseTotal());
            configurationAdjustmentTotal = addNonNegative(configurationAdjustmentTotal, quoted.configurationAdjustmentTotal());
            promotionAdjustmentTotal = addSigned(promotionAdjustmentTotal, quoted.promotionAdjustmentTotal());
            total = addNonNegative(total, quoted.total());
        }
        return new PromotionQuoteResponse(quotedAt, businessZone.getId(), lines, catalogBaseSubtotal,
                configurationAdjustmentTotal, promotionAdjustmentTotal, total);
    }

    private QuotedLine quoteLine(PromotionQuoteLineRequest line,
                                 List<Promotion> applicablePromotions) {
        String lineKey = line.lineKey().trim();
        MenuItemQuoteResponse catalogQuote = catalogConfigurationService.quote(line.menuItemId(),
                new MenuItemQuoteRequest(line.quantity(), line.groups()));
        MenuItem rootItem = menuCatalogRepository.findById(line.menuItemId()).orElseThrow(this::invalidQuote);
        Promotion promotion = resolvePromotion(rootItem, applicablePromotions);
        BigDecimal catalogBaseUnit = catalogQuote.baseUnitPrice();
        BigDecimal catalogBaseTotal = catalogQuote.baseTotal();
        BigDecimal configurationTotal = multiplyNonNegative(catalogQuote.unitAdjustmentTotal(), line.quantity());
        BigDecimal chargedBaseUnit = catalogBaseUnit;
        BigDecimal promotionAdjustment = zero();
        List<PromotionRewardQuoteResponse> rewards = List.of();

        if (promotion != null && promotion.getBenefitType() == PromotionBenefitType.FIXED_UNIT_PRICE) {
            chargedBaseUnit = promotion.getFixedUnitPriceAmount();
            promotionAdjustment = multiplySigned(chargedBaseUnit.subtract(catalogBaseUnit), line.quantity());
            rejectRewardConfigurations(line.rewardConfigurations());
        } else if (promotion != null && promotion.getBenefitType() == PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM) {
            int rewardCount = rewardCount(promotion, line.quantity());
            rewards = quoteRewards(lineKey, rootItem, promotion, line.rewardConfigurations(), rewardCount);
        } else {
            rejectRewardConfigurations(line.rewardConfigurations());
        }

        BigDecimal chargedBaseTotal = multiplyNonNegative(chargedBaseUnit, line.quantity());
        BigDecimal rewardTotal = rewards.stream().map(PromotionRewardQuoteResponse::total)
                .reduce(zero(), this::addNonNegative);
        BigDecimal rewardConfigurationTotal = rewards.stream()
                .map(PromotionRewardQuoteResponse::configurationAdjustmentTotal)
                .reduce(zero(), this::addNonNegative);
        BigDecimal lineTotal = addNonNegative(addNonNegative(chargedBaseTotal, configurationTotal), rewardTotal);
        PromotionQuoteLineResponse response = new PromotionQuoteLineResponse(lineKey, rootItem.getId(), rootItem.getName(),
                line.quantity(), catalogBaseUnit, chargedBaseUnit, catalogQuote,
                promotion == null ? null : AppliedPromotionResponse.from(promotion), promotionAdjustment, rewards, lineTotal);
        return new QuotedLine(response, catalogBaseTotal,
                addNonNegative(configurationTotal, rewardConfigurationTotal), promotionAdjustment, lineTotal);
    }

    private List<PromotionRewardQuoteResponse> quoteRewards(String lineKey,
                                                             MenuItem sourceItem,
                                                             Promotion promotion,
                                                             List<PromotionRewardConfigurationRequest> configurations,
                                                             int rewardCount) {
        Map<Integer, PromotionRewardConfigurationRequest> configurationsByOrdinal = indexRewardConfigurations(configurations,
                rewardCount);
        List<PromotionRewardQuoteResponse> rewards = new ArrayList<>();
        for (int ordinal = 1; ordinal <= rewardCount; ordinal++) {
            PromotionRewardConfigurationRequest configuration = configurationsByOrdinal.get(ordinal);
            MenuItemQuoteResponse rewardQuote = catalogConfigurationService.quote(sourceItem.getId(),
                    new MenuItemQuoteRequest(1, configuration == null ? List.of() : configuration.groups()));
            BigDecimal configurationTotal = rewardQuote.unitAdjustmentTotal();
            rewards.add(new PromotionRewardQuoteResponse(lineKey, ordinal, AppliedPromotionResponse.from(promotion),
                    sourceItem.getId(), sourceItem.getName(), rewardQuote.baseUnitPrice(), zero(), rewardQuote,
                    configurationTotal, configurationTotal));
        }
        return List.copyOf(rewards);
    }

    private Map<Integer, PromotionRewardConfigurationRequest> indexRewardConfigurations(
            List<PromotionRewardConfigurationRequest> configurations, int rewardCount) {
        Map<Integer, PromotionRewardConfigurationRequest> result = new HashMap<>();
        for (PromotionRewardConfigurationRequest configuration : configurations == null
                ? List.<PromotionRewardConfigurationRequest>of() : configurations) {
            if (configuration == null || configuration.rewardOrdinal() == null || configuration.rewardOrdinal() <= 0
                    || configuration.rewardOrdinal() > rewardCount
                    || result.putIfAbsent(configuration.rewardOrdinal(), configuration) != null) {
                throw new PromotionException(PromotionError.PROMOTION_REWARD_INVALID);
            }
        }
        return result;
    }

    private void rejectRewardConfigurations(List<PromotionRewardConfigurationRequest> configurations) {
        if (configurations != null && !configurations.isEmpty()) {
            throw new PromotionException(PromotionError.PROMOTION_REWARD_INVALID);
        }
    }

    private int rewardCount(Promotion promotion, int purchasedQuantity) {
        long cycles = promotion.getRepeatEnabled()
                ? purchasedQuantity / (long) promotion.getBuyQuantity()
                : (purchasedQuantity >= promotion.getBuyQuantity() ? 1L : 0L);
        try {
            return Math.toIntExact(Math.multiplyExact(cycles, (long) promotion.getRewardQuantity()));
        } catch (ArithmeticException exception) {
            throw invalidQuote();
        }
    }

    private Promotion resolvePromotion(MenuItem rootItem,
                                       List<Promotion> applicablePromotions) {
        List<Promotion> matches = applicablePromotions.stream()
                .filter(promotion -> targets(promotion, rootItem))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        int highestPriority = matches.get(0).getPriority();
        List<Promotion> highest = matches.stream().filter(promotion -> promotion.getPriority() == highestPriority).toList();
        if (highest.size() != 1) {
            throw new PromotionException(PromotionError.PROMOTION_CONFIGURATION_CONFLICT);
        }
        return highest.get(0);
    }

    private List<Promotion> applicablePromotions(LocalDate businessDate) {
        return promotionRepository.findByActiveTrueOrderByPriorityDescIdAsc().stream()
                .filter(promotion -> appliesOn(promotion, businessDate))
                .toList();
    }

    private boolean appliesOn(Promotion promotion, LocalDate businessDate) {
        return (promotion.getValidFrom() == null || !businessDate.isBefore(promotion.getValidFrom()))
                && (promotion.getValidUntil() == null || !businessDate.isAfter(promotion.getValidUntil()))
                && promotion.getIsoWeekdays().contains(businessDate.getDayOfWeek().getValue());
    }

    private boolean targets(Promotion promotion, MenuItem rootItem) {
        Set<Long> activeTagIds = rootItem.getTags().stream().filter(CatalogTag::isActive).map(CatalogTag::getId)
                .collect(java.util.stream.Collectors.toSet());
        return promotion.getTargets().stream().anyMatch(target ->
                target.getTargetMenuItem() != null && target.getTargetMenuItem().getId().equals(rootItem.getId())
                        || target.getTargetTag() != null && target.getTargetTag().isActive()
                        && activeTagIds.contains(target.getTargetTag().getId()));
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(CheckoutMoney.SCALE);
    }

    private BigDecimal addNonNegative(BigDecimal left, BigDecimal right) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(left.add(right));
        } catch (IllegalArgumentException exception) {
            throw invalidQuote();
        }
    }

    private BigDecimal multiplyNonNegative(BigDecimal amount, int quantity) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(amount.multiply(BigDecimal.valueOf(quantity)));
        } catch (IllegalArgumentException exception) {
            throw invalidQuote();
        }
    }

    private BigDecimal addSigned(BigDecimal left, BigDecimal right) {
        try {
            return checkoutMoney.normalizeSignedNumericAmount(left.add(right));
        } catch (IllegalArgumentException exception) {
            throw invalidQuote();
        }
    }

    private BigDecimal multiplySigned(BigDecimal amount, int quantity) {
        try {
            return checkoutMoney.normalizeSignedNumericAmount(amount.multiply(BigDecimal.valueOf(quantity)));
        } catch (IllegalArgumentException exception) {
            throw invalidQuote();
        }
    }

    private PromotionException invalidQuote() {
        return new PromotionException(PromotionError.PROMOTION_QUOTE_INVALID);
    }

    private record QuotedLine(PromotionQuoteLineResponse response,
                              BigDecimal catalogBaseTotal,
                              BigDecimal configurationAdjustmentTotal,
                              BigDecimal promotionAdjustmentTotal,
                              BigDecimal total) {
    }
}
