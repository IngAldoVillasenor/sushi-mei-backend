package com.sushimei.sushimei.backend.catalog;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, data-driven catalog configuration and quote boundary.
 * It has no order, cart, AI, HTTP-client, or conversation responsibility.
 */
@Service
public class CatalogConfigurationService {

    private static final int MAX_TAG_CODE_LENGTH = 64;
    private static final int MAX_TAG_NAME_LENGTH = 120;
    private static final int MAX_GROUP_NAME_LENGTH = 160;
    private static final int MAX_CONFIGURATION_DEPTH = 8;

    private static final Comparator<CatalogTagSummary> TAG_SUMMARY_ORDER =
            Comparator.comparingInt(CatalogTagSummary::displayOrder)
                    .thenComparing(CatalogTagSummary::code)
                    .thenComparing(CatalogTagSummary::id);

    private final MenuCatalogRepository menuCatalogRepository;
    private final CatalogTagRepository catalogTagRepository;
    private final MenuSelectionGroupRepository menuSelectionGroupRepository;
    private final MenuSelectionRuleRepository menuSelectionRuleRepository;
    private final MenuItemComponentService menuItemComponentService;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;

    public CatalogConfigurationService(MenuCatalogRepository menuCatalogRepository,
                                       CatalogTagRepository catalogTagRepository,
                                       MenuSelectionGroupRepository menuSelectionGroupRepository,
                                       MenuSelectionRuleRepository menuSelectionRuleRepository,
                                       MenuItemComponentService menuItemComponentService,
                                       CheckoutMoney checkoutMoney,
                                       Clock clock) {
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository, "menuCatalogRepository must not be null");
        this.catalogTagRepository = Objects.requireNonNull(catalogTagRepository, "catalogTagRepository must not be null");
        this.menuSelectionGroupRepository = Objects.requireNonNull(menuSelectionGroupRepository,
                "menuSelectionGroupRepository must not be null");
        this.menuSelectionRuleRepository = Objects.requireNonNull(menuSelectionRuleRepository,
                "menuSelectionRuleRepository must not be null");
        this.menuItemComponentService = Objects.requireNonNull(menuItemComponentService,
                "menuItemComponentService must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<CatalogTagResponse> listTags(boolean includeInactive) {
        List<CatalogTag> tags = includeInactive
                ? catalogTagRepository.findAllByOrderByDisplayOrderAscCodeAscIdAsc()
                : catalogTagRepository.findByActiveTrueOrderByDisplayOrderAscCodeAscIdAsc();
        return tags.stream().map(CatalogTagResponse::from).toList();
    }

    @Transactional
    public CatalogTagResponse createTag(CreateCatalogTagRequest request) {
        if (request == null) {
            throw invalidConfiguration();
        }
        String code = normalizeTagCode(request.code());
        if (catalogTagRepository.findByCode(code).isPresent()) {
            throw invalidConfiguration();
        }
        Instant now = clock.instant();
        CatalogTag tag = CatalogTag.create(code, normalizeRequiredText(request.name(), MAX_TAG_NAME_LENGTH),
                normalizeDisplayOrder(request.displayOrder(), 0), now);
        return CatalogTagResponse.from(catalogTagRepository.saveAndFlush(tag));
    }

    @Transactional
    public CatalogTagResponse updateTag(Long tagId, UpdateCatalogTagRequest request) {
        if (request == null || request.version() == null) {
            throw invalidConfiguration();
        }
        CatalogTag tag = findTag(tagId);
        verifyVersion(tag.getVersion(), request.version(), CatalogDomainError.CATALOG_TAG_VERSION_CONFLICT);
        tag.update(normalizeRequiredText(request.name(), MAX_TAG_NAME_LENGTH), requireBoolean(request.active()),
                normalizeDisplayOrder(request.displayOrder(), null), clock.instant());
        catalogTagRepository.flush();
        return CatalogTagResponse.from(tag);
    }

    @Transactional
    public void archiveTag(Long tagId) {
        CatalogTag tag = findTag(tagId);
        tag.archive(clock.instant());
    }

    @Transactional
    public MenuItemResponse replaceItemTags(Long itemId, ReplaceMenuItemTagsRequest request) {
        if (request == null || request.itemVersion() == null || request.tagIds() == null) {
            throw invalidConfiguration();
        }
        MenuItem item = findMenuItem(itemId);
        if (item.getVersion() != request.itemVersion()) {
            throw new MenuCatalogVersionConflictException();
        }
        Set<Long> requestedIds = new LinkedHashSet<>();
        for (Long tagId : request.tagIds()) {
            if (tagId == null || tagId <= 0 || !requestedIds.add(tagId)) {
                throw invalidConfiguration();
            }
        }
        List<CatalogTag> tags = catalogTagRepository.findAllById(requestedIds);
        if (tags.size() != requestedIds.size() || tags.stream().anyMatch(tag -> !tag.isActive())) {
            throw new CatalogConfigurationException(CatalogDomainError.CATALOG_TAG_NOT_FOUND);
        }
        item.replaceTags(new LinkedHashSet<>(tags), clock.instant());
        menuCatalogRepository.flush();
        boolean requiresConfiguration = !menuCatalogRepository
                .findIdsWithRequiredSelectionGroups(List.of(item.getId())).isEmpty();
        return MenuItemResponse.from(item, requiresConfiguration);
    }

    @Transactional
    public MenuSelectionGroupResponse createGroup(Long itemId, CreateMenuSelectionGroupRequest request) {
        if (request == null) {
            throw invalidConfiguration();
        }
        SelectionRange range = normalizeSelectionRange(request.minSelections(), request.maxSelections());
        Instant now = clock.instant();
        MenuSelectionGroup group = MenuSelectionGroup.create(findMenuItem(itemId),
                normalizeRequiredText(request.name(), MAX_GROUP_NAME_LENGTH), range.minSelections(), range.maxSelections(),
                requireBoolean(request.allowDuplicates()), normalizeDisplayOrder(request.displayOrder(), 0), now);
        return MenuSelectionGroupResponse.from(menuSelectionGroupRepository.saveAndFlush(group));
    }

    @Transactional
    public MenuSelectionGroupResponse updateGroup(Long itemId, Long groupId, UpdateMenuSelectionGroupRequest request) {
        if (request == null || request.version() == null) {
            throw invalidConfiguration();
        }
        MenuSelectionGroup group = findGroupForItem(itemId, groupId);
        verifyVersion(group.getVersion(), request.version(), CatalogDomainError.MENU_SELECTION_GROUP_VERSION_CONFLICT);
        SelectionRange range = normalizeSelectionRange(request.minSelections(), request.maxSelections());
        group.update(normalizeRequiredText(request.name(), MAX_GROUP_NAME_LENGTH), range.minSelections(), range.maxSelections(),
                requireBoolean(request.allowDuplicates()), normalizeDisplayOrder(request.displayOrder(), null),
                requireBoolean(request.active()), clock.instant());
        menuSelectionGroupRepository.flush();
        return MenuSelectionGroupResponse.from(group);
    }

    @Transactional
    public void archiveGroup(Long itemId, Long groupId) {
        MenuSelectionGroup group = findGroupForItem(itemId, groupId);
        group.archive(clock.instant());
    }

    @Transactional
    public MenuSelectionRuleResponse createRule(Long groupId, CreateMenuSelectionRuleRequest request) {
        if (request == null) {
            throw invalidConfiguration();
        }
        MenuSelectionGroup group = findGroup(groupId);
        RuleTarget target = resolveTarget(request.targetType(), request.targetId());
        RulePricing pricing = normalizeRulePricing(request.pricingPolicy(), request.referencePrice(), request.fixedSurcharge());
        int priority = normalizePriority(request.priority());
        Instant now = clock.instant();
        MenuSelectionRule rule = MenuSelectionRule.create(group, target.menuItem(), target.tag(), pricing.policy(),
                pricing.referencePrice(), pricing.fixedSurcharge(), priority, now);
        return MenuSelectionRuleResponse.from(menuSelectionRuleRepository.saveAndFlush(rule));
    }

    @Transactional
    public MenuSelectionRuleResponse updateRule(Long groupId, Long ruleId, UpdateMenuSelectionRuleRequest request) {
        if (request == null || request.version() == null) {
            throw invalidConfiguration();
        }
        MenuSelectionRule rule = findRuleForGroup(groupId, ruleId);
        verifyVersion(rule.getVersion(), request.version(), CatalogDomainError.MENU_SELECTION_RULE_VERSION_CONFLICT);
        RuleTarget target = resolveTarget(request.targetType(), request.targetId());
        RulePricing pricing = normalizeRulePricing(request.pricingPolicy(), request.referencePrice(), request.fixedSurcharge());
        rule.update(target.menuItem(), target.tag(), pricing.policy(), pricing.referencePrice(), pricing.fixedSurcharge(),
                normalizePriority(request.priority()), requireBoolean(request.active()), clock.instant());
        menuSelectionRuleRepository.flush();
        return MenuSelectionRuleResponse.from(rule);
    }

    @Transactional
    public void archiveRule(Long groupId, Long ruleId) {
        findRuleForGroup(groupId, ruleId).archive(clock.instant());
    }

    @Transactional(readOnly = true)
    public MenuItemConfigurationResponse operationalConfiguration(Long itemId) {
        MenuItem item = findMenuItem(itemId);
        List<MenuItem> candidates = menuCatalogRepository.findByActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
        List<MenuSelectionGroupConfigurationResponse> groups = activeGroupsFor(item).stream()
                .map(group -> operationalGroup(group, candidates))
                .toList();
        return new MenuItemConfigurationResponse(item.getId(), item.getName(), item.isStandaloneOrderable(),
                requireNonNegativeMoney(item.getPriceAmount()), groups.stream().anyMatch(group -> group.minSelections() > 0), groups);
    }

    @Transactional(readOnly = true)
    public MenuItemConfigurationDefinitionResponse configurationDefinition(Long itemId) {
        MenuItem item = findMenuItem(itemId);
        List<CatalogTagSummary> tags = item.getTags().stream().map(CatalogTagSummary::from)
                .sorted(TAG_SUMMARY_ORDER).toList();
        List<MenuSelectionGroupDefinitionResponse> groups = allGroupsFor(item).stream()
                .map(group -> new MenuSelectionGroupDefinitionResponse(MenuSelectionGroupResponse.from(group),
                        menuSelectionRuleRepository.findBySelectionGroupIdOrderByPriorityDescIdAsc(group.getId()).stream()
                                .map(MenuSelectionRuleResponse::from).toList()))
                .toList();
        return new MenuItemConfigurationDefinitionResponse(item.getId(), item.getName(), item.getVersion(), tags, groups);
    }

    @Transactional(readOnly = true)
    public MenuItemQuoteResponse quote(Long itemId, MenuItemQuoteRequest request) {
        if (request == null) {
            throw invalidConfiguration();
        }
        int quantity = requirePositiveQuantity(request.quantity());
        MenuItem root = findMenuItem(itemId);
        if (!root.isActive() || !root.isAvailable()) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_ITEM_UNAVAILABLE);
        }
        if (!root.isStandaloneOrderable()) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_ITEM_NOT_ORDERABLE);
        }
        ConfiguredNode configuration = configureItem(root, request.groups(), new LinkedHashSet<>(Set.of(root.getId())), 0);
        BigDecimal baseUnitPrice = root.getPricingMode() == MenuItemPricingMode.SELECTION_SUM
                ? zeroAmount()
                : requirePositiveMoney(root.getPriceAmount());
        BigDecimal baseTotal = multiply(baseUnitPrice, quantity);
        BigDecimal unitTotal = requirePositiveMoney(baseUnitPrice.add(configuration.unitAdjustmentTotal()));
        return new MenuItemQuoteResponse(root.getId(), root.getName(), quantity, baseUnitPrice, baseTotal,
                configuration.groups(), configuration.unitAdjustmentTotal(), unitTotal, multiply(unitTotal, quantity));
    }

    private MenuSelectionGroupConfigurationResponse operationalGroup(MenuSelectionGroup group, List<MenuItem> candidates) {
        List<MenuSelectionRule> rules = menuSelectionRuleRepository
                .findBySelectionGroupIdAndActiveTrueOrderByPriorityDescIdAsc(group.getId());
        List<MenuSelectionOptionResponse> options = new ArrayList<>();
        Long parentMenuItemId = group.getParentMenuItem().getId();
        for (MenuItem candidate : candidates) {
            if (candidate.getId().equals(parentMenuItemId)) {
                continue;
            }
            MenuSelectionRule rule = chooseMatchingRule(rules, candidate);
            if (rule != null) {
                options.add(new MenuSelectionOptionResponse(candidate.getId(), candidate.getName(), candidate.getCategory(),
                        requirePositiveMoney(candidate.getPriceAmount()), candidate.isAvailable(),
                        activeGroupsFor(candidate).stream().anyMatch(activeGroup -> activeGroup.getMinSelections() > 0), ruleAdjustment(rule, candidate)));
            }
        }
        return new MenuSelectionGroupConfigurationResponse(group.getId(), group.getName(), group.getMinSelections(),
                group.getMaxSelections(), group.isAllowDuplicates(), options);
    }

    private ConfiguredNode configureItem(MenuItem item,
                                         List<MenuQuoteGroupRequest> requestGroups,
                                         Set<Long> ancestorItemIds,
                                         int depth) {
        if (depth > MAX_CONFIGURATION_DEPTH) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INVALID);
        }
        Map<Long, MenuSelectionGroup> activeGroups = new LinkedHashMap<>();
        for (MenuSelectionGroup group : activeGroupsFor(item)) {
            activeGroups.put(group.getId(), group);
        }
        Map<Long, MenuQuoteGroupRequest> requestedGroups = indexRequestedGroups(requestGroups, activeGroups);
        List<MenuQuoteGroupResponse> quotedGroups = new ArrayList<>();
        BigDecimal adjustments = zeroAmount();

        for (MenuSelectionGroup group : activeGroups.values()) {
            MenuQuoteGroupRequest requestedGroup = requestedGroups.get(group.getId());
            List<MenuQuoteSelectionRequest> selections = requestedGroup == null ? List.of() : requestedGroup.selections();
            SelectionCount count = validateSelections(group, selections);
            if (count.totalQuantity() < group.getMinSelections()) {
                throw new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INCOMPLETE);
            }
            if (count.totalQuantity() > group.getMaxSelections()) {
                throw invalidConfiguration();
            }

            List<MenuSelectionRule> rules = menuSelectionRuleRepository
                    .findBySelectionGroupIdAndActiveTrueOrderByPriorityDescIdAsc(group.getId());
            List<MenuQuoteSelectionResponse> quotedSelections = new ArrayList<>();
            for (MenuQuoteSelectionRequest selection : selections) {
                MenuItem selectedItem = findSelectedItem(selection.menuItemId());
                if (!selectedItem.isActive() || !selectedItem.isAvailable()) {
                    throw new CatalogConfigurationException(CatalogDomainError.MENU_ITEM_UNAVAILABLE);
                }
                if (ancestorItemIds.contains(selectedItem.getId())) {
                    throw new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_CYCLE);
                }
                MenuSelectionRule rule = chooseMatchingRule(rules, selectedItem);
                if (rule == null) {
                    throw new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_NOT_ALLOWED);
                }
                Set<Long> descendants = new LinkedHashSet<>(ancestorItemIds);
                descendants.add(selectedItem.getId());
                ConfiguredNode nested = configureItem(selectedItem, selection.groups(), descendants, depth + 1);
                List<DefaultComponentResponse> omittedComponents = menuItemComponentService
                        .resolveActiveOmittedComponents(selectedItem.getId(), selection.omittedComponentIds()).stream()
                        .map(DefaultComponentResponse::from)
                        .toList();
                BigDecimal adjustment = ruleAdjustment(rule, selectedItem);
                BigDecimal contributionPerUnit = normalizeNonNegative(adjustment.add(nested.unitAdjustmentTotal()));
                adjustments = normalizeNonNegative(adjustments.add(multiply(contributionPerUnit, selection.quantity())));
                quotedSelections.add(new MenuQuoteSelectionResponse(selectedItem.getId(), selectedItem.getName(),
                        selection.quantity(), requirePositiveMoney(selectedItem.getPriceAmount()), adjustment,
                        selectedItem.isStandaloneOrderable() || nested.groups().isEmpty(), nested.groups(),
                        omittedComponents, normalizeOptionalNote(selection.note())));
            }
            quotedGroups.add(new MenuQuoteGroupResponse(group.getId(), group.getName(), quotedSelections));
        }
        return new ConfiguredNode(quotedGroups, adjustments);
    }

    private Map<Long, MenuQuoteGroupRequest> indexRequestedGroups(List<MenuQuoteGroupRequest> requestGroups,
                                                                    Map<Long, MenuSelectionGroup> activeGroups) {
        Map<Long, MenuQuoteGroupRequest> result = new HashMap<>();
        for (MenuQuoteGroupRequest group : requestGroups == null ? List.<MenuQuoteGroupRequest>of() : requestGroups) {
            if (group == null || group.groupId() == null || group.groupId() <= 0
                    || !activeGroups.containsKey(group.groupId()) || result.putIfAbsent(group.groupId(), group) != null) {
                throw invalidConfiguration();
            }
        }
        return result;
    }

    private SelectionCount validateSelections(MenuSelectionGroup group, List<MenuQuoteSelectionRequest> selections) {
        int total = 0;
        Set<Long> selectedItemIds = new HashSet<>();
        for (MenuQuoteSelectionRequest selection : selections) {
            if (selection == null || selection.menuItemId() == null || selection.menuItemId() <= 0) {
                throw invalidConfiguration();
            }
            int quantity = requirePositiveQuantity(selection.quantity());
            if ((!group.isAllowDuplicates() && !selectedItemIds.add(selection.menuItemId()))
                    || (!group.isAllowDuplicates() && quantity > 1)) {
                throw new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_DUPLICATE_NOT_ALLOWED);
            }
            try {
                total = Math.addExact(total, quantity);
            } catch (ArithmeticException exception) {
                throw invalidConfiguration();
            }
        }
        return new SelectionCount(total);
    }

    private MenuSelectionRule chooseMatchingRule(List<MenuSelectionRule> rules, MenuItem selectedItem) {
        List<MenuSelectionRule> matches = rules.stream().filter(rule -> matches(rule, selectedItem)).toList();
        if (matches.isEmpty()) {
            return null;
        }
        int priority = matches.get(0).getPriority();
        if (matches.stream().filter(rule -> rule.getPriority() == priority).count() != 1) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INVALID);
        }
        return matches.get(0);
    }

    private boolean matches(MenuSelectionRule rule, MenuItem selectedItem) {
        if (rule.getTargetMenuItem() != null) {
            return rule.getTargetMenuItem().getId().equals(selectedItem.getId());
        }
        if (rule.getTargetTag() == null) {
            throw invalidConfiguration();
        }
        Long targetTagId = rule.getTargetTag().getId();
        return selectedItem.getTags().stream()
                .filter(CatalogTag::isActive)
                .anyMatch(tag -> tag.getId().equals(targetTagId));
    }

    private BigDecimal ruleAdjustment(MenuSelectionRule rule, MenuItem selectedItem) {
        BigDecimal selectedPrice = requirePositiveMoney(selectedItem.getPriceAmount());
        return switch (rule.getPricingPolicy()) {
            case INCLUDED -> requireNoRuleAmounts(rule) ? zeroAmount() : invalidRuleAmount();
            case FULL_ITEM_PRICE -> requireNoRuleAmounts(rule) ? selectedPrice : invalidRuleAmount();
            case PRICE_DIFFERENCE -> {
                if (rule.getFixedSurchargeAmount() != null) {
                    throw invalidConfiguration();
                }
                BigDecimal reference = requirePositiveMoney(rule.getReferencePriceAmount());
                yield normalizeNonNegative(selectedPrice.subtract(reference).max(BigDecimal.ZERO));
            }
            case FIXED_SURCHARGE -> {
                if (rule.getReferencePriceAmount() != null) {
                    throw invalidConfiguration();
                }
                yield normalizeNonNegative(rule.getFixedSurchargeAmount());
            }
        };
    }

    private boolean requireNoRuleAmounts(MenuSelectionRule rule) {
        if (rule.getReferencePriceAmount() != null || rule.getFixedSurchargeAmount() != null) {
            throw invalidConfiguration();
        }
        return true;
    }

    private BigDecimal invalidRuleAmount() {
        throw invalidConfiguration();
    }

    private List<MenuSelectionGroup> activeGroupsFor(MenuItem item) {
        return menuSelectionGroupRepository.findByParentMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(item.getId());
    }

    private List<MenuSelectionGroup> allGroupsFor(MenuItem item) {
        return menuSelectionGroupRepository.findByParentMenuItemIdOrderByDisplayOrderAscIdAsc(item.getId());
    }

    private MenuItem findMenuItem(Long itemId) {
        if (itemId == null || itemId <= 0) {
            throw new MenuCatalogItemNotFoundException();
        }
        return menuCatalogRepository.findById(itemId).orElseThrow(MenuCatalogItemNotFoundException::new);
    }

    private MenuItem findSelectedItem(Long itemId) {
        if (itemId == null || itemId <= 0) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_NOT_ALLOWED);
        }
        return menuCatalogRepository.findById(itemId)
                .orElseThrow(() -> new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_NOT_ALLOWED));
    }

    private CatalogTag findTag(Long tagId) {
        if (tagId == null || tagId <= 0) {
            throw new CatalogConfigurationException(CatalogDomainError.CATALOG_TAG_NOT_FOUND);
        }
        return catalogTagRepository.findById(tagId)
                .orElseThrow(() -> new CatalogConfigurationException(CatalogDomainError.CATALOG_TAG_NOT_FOUND));
    }

    private MenuSelectionGroup findGroup(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_GROUP_NOT_FOUND);
        }
        return menuSelectionGroupRepository.findById(groupId)
                .orElseThrow(() -> new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_GROUP_NOT_FOUND));
    }

    private MenuSelectionGroup findGroupForItem(Long itemId, Long groupId) {
        MenuItem item = findMenuItem(itemId);
        if (groupId == null || groupId <= 0) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_GROUP_NOT_FOUND);
        }
        return menuSelectionGroupRepository.findByIdAndParentMenuItemId(groupId, item.getId())
                .orElseThrow(() -> new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_GROUP_NOT_FOUND));
    }

    private MenuSelectionRule findRuleForGroup(Long groupId, Long ruleId) {
        MenuSelectionGroup group = findGroup(groupId);
        if (ruleId == null || ruleId <= 0) {
            throw new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_RULE_NOT_FOUND);
        }
        return menuSelectionRuleRepository.findByIdAndSelectionGroupId(ruleId, group.getId())
                .orElseThrow(() -> new CatalogConfigurationException(CatalogDomainError.MENU_SELECTION_RULE_NOT_FOUND));
    }

    private RuleTarget resolveTarget(SelectionRuleTargetType targetType, Long targetId) {
        if (targetType == null || targetId == null || targetId <= 0) {
            throw invalidConfiguration();
        }
        return switch (targetType) {
            case ITEM -> new RuleTarget(findMenuItem(targetId), null);
            case TAG -> new RuleTarget(null, findTag(targetId));
        };
    }

    private RulePricing normalizeRulePricing(SelectionPricingPolicy policy,
                                             BigDecimal referencePrice,
                                             BigDecimal fixedSurcharge) {
        if (policy == null) {
            throw invalidConfiguration();
        }
        return switch (policy) {
            case INCLUDED, FULL_ITEM_PRICE -> {
                if (referencePrice != null || fixedSurcharge != null) {
                    throw invalidConfiguration();
                }
                yield new RulePricing(policy, null, null);
            }
            case PRICE_DIFFERENCE -> {
                if (fixedSurcharge != null) {
                    throw invalidConfiguration();
                }
                yield new RulePricing(policy, requirePositiveMoney(referencePrice), null);
            }
            case FIXED_SURCHARGE -> {
                if (referencePrice != null) {
                    throw invalidConfiguration();
                }
                yield new RulePricing(policy, null, normalizeNonNegative(fixedSurcharge));
            }
        };
    }

    private SelectionRange normalizeSelectionRange(Integer minSelections, Integer maxSelections) {
        if (minSelections == null || maxSelections == null || minSelections < 0 || maxSelections <= 0
                || maxSelections < minSelections) {
            throw invalidConfiguration();
        }
        return new SelectionRange(minSelections, maxSelections);
    }

    private int normalizePriority(Integer priority) {
        if (priority == null || priority < 0) {
            throw invalidConfiguration();
        }
        return priority;
    }

    private String normalizeTagCode(String code) {
        return normalizeRequiredText(code, MAX_TAG_CODE_LENGTH).toUpperCase(Locale.ROOT);
    }

    private String normalizeRequiredText(String value, int maximumLength) {
        if (value == null) {
            throw invalidConfiguration();
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw invalidConfiguration();
        }
        return normalized;
    }

    private String normalizeOptionalNote(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 500) {
            throw invalidConfiguration();
        }
        return normalized;
    }

    private int normalizeDisplayOrder(Integer displayOrder, Integer defaultValue) {
        Integer value = displayOrder == null ? defaultValue : displayOrder;
        if (value == null || value < 0) {
            throw invalidConfiguration();
        }
        return value;
    }

    private boolean requireBoolean(Boolean value) {
        if (value == null) {
            throw invalidConfiguration();
        }
        return value;
    }

    private int requirePositiveQuantity(Integer quantity) {
        try {
            return checkoutMoney.requirePositiveQuantity(quantity);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
    }

    private BigDecimal requirePositiveMoney(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
    }

    private BigDecimal requireNonNegativeMoney(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
    }

    private BigDecimal normalizeNonNegative(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
    }

    private BigDecimal multiply(BigDecimal unitAmount, int quantity) {
        try {
            return normalizeNonNegative(unitAmount.multiply(BigDecimal.valueOf(quantity)));
        } catch (ArithmeticException exception) {
            throw invalidConfiguration();
        }
    }

    private BigDecimal zeroAmount() {
        return BigDecimal.ZERO.setScale(CheckoutMoney.SCALE);
    }

    private void verifyVersion(long actual, Long expected, CatalogDomainError error) {
        if (expected == null || actual != expected) {
            throw new CatalogConfigurationException(error);
        }
    }

    private CatalogConfigurationException invalidConfiguration() {
        return new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INVALID);
    }

    private record RuleTarget(MenuItem menuItem, CatalogTag tag) {
    }

    private record RulePricing(SelectionPricingPolicy policy,
                               BigDecimal referencePrice,
                               BigDecimal fixedSurcharge) {
    }

    private record SelectionRange(int minSelections, int maxSelections) {
    }

    private record SelectionCount(int totalQuantity) {
    }

    private record ConfiguredNode(List<MenuQuoteGroupResponse> groups, BigDecimal unitAdjustmentTotal) {
        private ConfiguredNode {
            groups = List.copyOf(groups);
            Objects.requireNonNull(unitAdjustmentTotal, "unitAdjustmentTotal must not be null");
        }
    }
}
