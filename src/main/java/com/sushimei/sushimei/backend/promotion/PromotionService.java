package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.CatalogTag;
import com.sushimei.sushimei.backend.catalog.CatalogTagRepository;
import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PromotionService {

    private static final int MAX_NAME_LENGTH = 160;
    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionService.class);

    private final PromotionRepository promotionRepository;
    private final MenuCatalogRepository menuCatalogRepository;
    private final CatalogTagRepository catalogTagRepository;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;

    public PromotionService(PromotionRepository promotionRepository,
                            MenuCatalogRepository menuCatalogRepository,
                            CatalogTagRepository catalogTagRepository,
                            CheckoutMoney checkoutMoney,
                            Clock clock) {
        this.promotionRepository = Objects.requireNonNull(promotionRepository, "promotionRepository must not be null");
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository, "menuCatalogRepository must not be null");
        this.catalogTagRepository = Objects.requireNonNull(catalogTagRepository, "catalogTagRepository must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> list(boolean includeInactive) {
        List<Promotion> promotions = includeInactive
                ? promotionRepository.findAllByOrderByPriorityDescIdAsc()
                : promotionRepository.findByActiveTrueOrderByPriorityDescIdAsc();
        return promotions.stream().map(PromotionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PromotionResponse get(Long id) {
        return PromotionResponse.from(findPromotion(id));
    }

    @Transactional
    public PromotionResponse create(CreatePromotionRequest request) {
        if (request == null) {
            throw invalid();
        }
        NormalizedPromotion normalized = normalize(request.name(), request.active() == null || request.active(), request.priority(),
                request.benefitType(), request.fixedUnitPrice(), request.buyQuantity(), request.rewardQuantity(), request.repeat(),
                request.validFrom(), request.validUntil(), request.daysOfWeek(), request.targets());
        validateNoScheduleConflict(null, normalized);
        Instant now = clock.instant();
        Promotion promotion = Promotion.create(normalized.name(), normalized.active(), normalized.priority(), normalized.benefitType(),
                normalized.fixedUnitPrice(), normalized.buyQuantity(), normalized.rewardQuantity(), normalized.repeat(),
                normalized.validFrom(), normalized.validUntil(), normalized.daysOfWeek(), normalized.targets(), now);
        return PromotionResponse.from(promotionRepository.saveAndFlush(promotion));
    }

    @Transactional
    public PromotionResponse update(Long id, UpdatePromotionRequest request) {
        if (request == null || request.version() == null) {
            throw invalid();
        }
        Promotion promotion = findPromotion(id);
        if (promotion.getVersion() != request.version()) {
            throw new PromotionException(PromotionError.PROMOTION_VERSION_CONFLICT);
        }
        NormalizedPromotion normalized = normalize(request.name(), requireBoolean(request.active()), request.priority(), request.benefitType(),
                request.fixedUnitPrice(), request.buyQuantity(), request.rewardQuantity(), request.repeat(), request.validFrom(),
                request.validUntil(), request.daysOfWeek(), request.targets());
        validateNoScheduleConflict(promotion.getId(), normalized);
        promotion.update(normalized.name(), normalized.active(), normalized.priority(), normalized.benefitType(),
                normalized.fixedUnitPrice(), normalized.buyQuantity(), normalized.rewardQuantity(), normalized.repeat(),
                normalized.validFrom(), normalized.validUntil(), normalized.daysOfWeek(), normalized.targets(), clock.instant());
        promotionRepository.flush();
        return PromotionResponse.from(promotion);
    }

    @Transactional
    public void archive(Long id) {
        findPromotion(id).archive(clock.instant());
    }

    private NormalizedPromotion normalize(String name,
                                          boolean active,
                                          Integer priority,
                                          PromotionBenefitType benefitType,
                                          BigDecimal fixedUnitPrice,
                                          Integer buyQuantity,
                                          Integer rewardQuantity,
                                          Boolean repeat,
                                          LocalDate validFrom,
                                          LocalDate validUntil,
                                          Set<Integer> daysOfWeek,
                                          List<PromotionTargetRequest> targets) {
        String normalizedName = normalizeName(name);
        if (priority == null || priority < 0 || benefitType == null || daysOfWeek == null || daysOfWeek.isEmpty()
                || targets == null || targets.isEmpty() || (validFrom != null && validUntil != null && validUntil.isBefore(validFrom))) {
            throw invalid();
        }
        Set<Integer> normalizedWeekdays = new LinkedHashSet<>();
        for (Integer day : daysOfWeek) {
            if (day == null || day < 1 || day > 7 || !normalizedWeekdays.add(day)) {
                throw invalid();
            }
        }
        RuleParameters parameters = normalizeParameters(benefitType, fixedUnitPrice, buyQuantity, rewardQuantity, repeat);
        return new NormalizedPromotion(normalizedName, active, priority, benefitType, parameters.fixedUnitPrice(),
                parameters.buyQuantity(), parameters.rewardQuantity(), parameters.repeat(), validFrom, validUntil,
                normalizedWeekdays, resolveTargets(targets));
    }

    private RuleParameters normalizeParameters(PromotionBenefitType benefitType,
                                               BigDecimal fixedUnitPrice,
                                               Integer buyQuantity,
                                               Integer rewardQuantity,
                                               Boolean repeat) {
        if (benefitType == PromotionBenefitType.FIXED_UNIT_PRICE) {
            if (buyQuantity != null || rewardQuantity != null || repeat != null) {
                throw invalid();
            }
            return new RuleParameters(normalizePositiveMoney(fixedUnitPrice), null, null, null);
        }
        if (benefitType == PromotionBenefitType.BUY_X_GET_Y_SAME_ITEM) {
            if (fixedUnitPrice != null || buyQuantity == null || buyQuantity <= 0 || rewardQuantity == null
                    || rewardQuantity <= 0 || repeat == null) {
                throw invalid();
            }
            return new RuleParameters(null, buyQuantity, rewardQuantity, repeat);
        }
        throw invalid();
    }

    private List<PromotionTargetDraft> resolveTargets(List<PromotionTargetRequest> requests) {
        List<PromotionTargetDraft> targets = new ArrayList<>();
        Set<String> distinctTargets = new LinkedHashSet<>();
        for (PromotionTargetRequest request : requests) {
            if (request == null || request.targetType() == null || request.targetId() == null || request.targetId() <= 0) {
                throw invalid();
            }
            String key = request.targetType().name() + ':' + request.targetId();
            if (!distinctTargets.add(key)) {
                throw invalid();
            }
            if (request.targetType() == PromotionTargetType.ITEM) {
                MenuItem item = menuCatalogRepository.findById(request.targetId()).orElseThrow(this::invalid);
                targets.add(new PromotionTargetDraft(item, null));
            } else if (request.targetType() == PromotionTargetType.TAG) {
                CatalogTag tag = catalogTagRepository.findById(request.targetId()).filter(CatalogTag::isActive)
                        .orElseThrow(this::invalid);
                targets.add(new PromotionTargetDraft(null, tag));
            } else {
                throw invalid();
            }
        }
        return targets;
    }

    private void validateNoScheduleConflict(Long promotionId, NormalizedPromotion candidate) {
        if (!candidate.active()) {
            return;
        }
        List<MenuItem> catalogItems = menuCatalogRepository.findAllByOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
        Set<Long> candidateItemIds = targetedItemIds(candidate.targets(), catalogItems);
        Promotion conflict = promotionRepository.findByActiveTrueOrderByPriorityDescIdAsc().stream()
                .filter(existing -> !Objects.equals(existing.getId(), promotionId))
                .filter(existing -> existing.getPriority() == candidate.priority())
                .filter(existing -> weekdaysOverlap(existing.getIsoWeekdays(), candidate.daysOfWeek()))
                .filter(existing -> dateRangesOverlap(existing.getValidFrom(), existing.getValidUntil(),
                        candidate.validFrom(), candidate.validUntil()))
                .filter(existing -> targetsOverlap(existing, candidate.targets(), candidateItemIds, catalogItems))
                .findFirst()
                .orElse(null);
        if (conflict != null) {
            LOGGER.warn("promotion_schedule_conflict requestId={} candidateId={} conflictingId={} priority={}",
                    MDC.get("requestId"), promotionId, conflict.getId(), candidate.priority());
            throw new PromotionException(PromotionError.PROMOTION_SCHEDULE_CONFLICT);
        }
    }

    private boolean targetsOverlap(Promotion existing,
                                   List<PromotionTargetDraft> candidateTargets,
                                   Set<Long> candidateItemIds,
                                   List<MenuItem> catalogItems) {
        List<PromotionTargetDraft> existingTargets = existing.getTargets().stream()
                .map(target -> new PromotionTargetDraft(target.getTargetMenuItem(), target.getTargetTag()))
                .toList();
        if (directTargetsOverlap(existingTargets, candidateTargets)) {
            return true;
        }
        Set<Long> existingItemIds = targetedItemIds(existingTargets, catalogItems);
        return existingItemIds.stream().anyMatch(candidateItemIds::contains);
    }

    private boolean directTargetsOverlap(List<PromotionTargetDraft> left, List<PromotionTargetDraft> right) {
        return left.stream().anyMatch(leftTarget -> right.stream().anyMatch(rightTarget ->
                (leftTarget.targetMenuItem() != null && rightTarget.targetMenuItem() != null
                        && Objects.equals(leftTarget.targetMenuItem().getId(), rightTarget.targetMenuItem().getId()))
                        || (leftTarget.targetTag() != null && rightTarget.targetTag() != null
                        && Objects.equals(leftTarget.targetTag().getId(), rightTarget.targetTag().getId()))));
    }

    private Set<Long> targetedItemIds(List<PromotionTargetDraft> targets, List<MenuItem> catalogItems) {
        Set<Long> itemIds = new HashSet<>();
        Set<Long> tagIds = new HashSet<>();
        for (PromotionTargetDraft target : targets) {
            if (target.targetMenuItem() != null) {
                itemIds.add(target.targetMenuItem().getId());
            }
            if (target.targetTag() != null && target.targetTag().isActive()) {
                tagIds.add(target.targetTag().getId());
            }
        }
        if (!tagIds.isEmpty()) {
            catalogItems.stream()
                    .filter(item -> item.getTags().stream().anyMatch(tag -> tagIds.contains(tag.getId())))
                    .map(MenuItem::getId)
                    .forEach(itemIds::add);
        }
        return itemIds;
    }

    private boolean weekdaysOverlap(Set<Integer> left, Set<Integer> right) {
        return left.stream().anyMatch(right::contains);
    }

    private boolean dateRangesOverlap(LocalDate leftFrom,
                                      LocalDate leftUntil,
                                      LocalDate rightFrom,
                                      LocalDate rightUntil) {
        return (leftUntil == null || rightFrom == null || !leftUntil.isBefore(rightFrom))
                && (rightUntil == null || leftFrom == null || !rightUntil.isBefore(leftFrom));
    }

    private Promotion findPromotion(Long id) {
        if (id == null || id <= 0) {
            throw new PromotionException(PromotionError.PROMOTION_NOT_FOUND);
        }
        return promotionRepository.findById(id).orElseThrow(() -> new PromotionException(PromotionError.PROMOTION_NOT_FOUND));
    }

    private String normalizeName(String name) {
        if (name == null) {
            throw invalid();
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw invalid();
        }
        return normalized;
    }

    private BigDecimal normalizePositiveMoney(BigDecimal value) {
        try {
            return checkoutMoney.normalizeNumericAmount(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private boolean requireBoolean(Boolean value) {
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private PromotionException invalid() {
        return new PromotionException(PromotionError.INVALID_PROMOTION);
    }

    private record RuleParameters(BigDecimal fixedUnitPrice, Integer buyQuantity, Integer rewardQuantity, Boolean repeat) {
    }

    private record NormalizedPromotion(String name, boolean active, int priority, PromotionBenefitType benefitType,
                                       BigDecimal fixedUnitPrice, Integer buyQuantity, Integer rewardQuantity, Boolean repeat,
                                       LocalDate validFrom, LocalDate validUntil, Set<Integer> daysOfWeek,
                                       List<PromotionTargetDraft> targets) {
    }
}
