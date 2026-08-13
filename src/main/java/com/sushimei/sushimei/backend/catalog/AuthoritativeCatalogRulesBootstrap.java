package com.sushimei.sushimei.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the reviewed operational catalog rule set exactly once. It reads the
 * repository's verified base-menu resource only when an empty catalog needs its
 * initial 121 rows; it never invokes RAG ingestion or writes descriptions.
 */
@Component
class AuthoritativeCatalogRulesBootstrap implements ApplicationRunner {

    private final AuthoritativeCatalogRulesService rulesService;

    AuthoritativeCatalogRulesBootstrap(AuthoritativeCatalogRulesService rulesService) {
        this.rulesService = rulesService;
    }

    @Override
    public void run(ApplicationArguments args) {
        rulesService.synchronize();
    }
}

@Service
class AuthoritativeCatalogRulesService {

    private static final String CATEGORY = "Charolas/Sushi Box";
    private static final String DRINK_PACKAGE_NAME = "Paquete 2 bebidas Sushi Box";
    static final String RULE_SET_ID = "PHASE_6F1_AUTHORITATIVE_CATALOG_RULES";

    private static final Set<Long> REQUIRED_PRODUCT_IDS = Set.of(
            1L, 2L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L,
            32L, 33L, 35L, 36L, 37L, 38L, 41L, 42L, 47L, 48L, 49L, 50L, 51L, 52L, 53L,
            58L, 66L, 67L, 68L, 74L, 79L, 80L, 82L, 83L, 85L, 95L, 96L, 97L, 105L, 106L,
            107L, 108L);

    private final MenuCatalogRepository menuItems;
    private final CatalogTagRepository tags;
    private final MenuSelectionGroupRepository groups;
    private final MenuSelectionRuleRepository rules;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final CheckoutMoney checkoutMoney;

    AuthoritativeCatalogRulesService(MenuCatalogRepository menuItems,
                                     CatalogTagRepository tags,
                                     MenuSelectionGroupRepository groups,
                                     MenuSelectionRuleRepository rules,
                                     JdbcTemplate jdbcTemplate,
                                     Clock clock,
                                     ObjectMapper objectMapper,
                                     CheckoutMoney checkoutMoney) {
        this.menuItems = menuItems;
        this.tags = tags;
        this.groups = groups;
        this.rules = rules;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.checkoutMoney = checkoutMoney;
    }

    @Transactional
    public void synchronize() {
        if (isRuleSetAppliedWithLock()) {
            return;
        }

        Instant now = clock.instant();
        OffsetDateTime databaseNow = jdbcTimestamp(now);
        Map<Long, BaseCatalogItem> baseCatalog = loadBaseCatalog();
        initializeEmptyBaseCatalog(baseCatalog, databaseNow);

        Map<Long, MenuItem> items = requireExpectedItems(baseCatalog);
        Map<String, CatalogTag> authoritativeTags = synchronizeTags(now);
        synchronizeTagMembership(authoritativeTags, now);
        archiveDiscontinuedItems(items, now);

        configureFixedCharolas(items, authoritativeTags, now);
        configureBuildYourOwnCharolas(authoritativeTags, now);
        configureSushiBoxes(items, authoritativeTags, now);
        menuItems.flush();
        groups.flush();
        rules.flush();
        int marked = jdbcTemplate.update("""
                update public.catalog_bootstrap_rule_sets
                set applied_at = ?
                where rule_set_id = ? and applied_at is null
                """, databaseNow, RULE_SET_ID);
        if (marked != 1) {
            throw new IllegalStateException("Authoritative catalog rule set could not be marked as applied");
        }
    }

    private boolean isRuleSetAppliedWithLock() {
        try {
            Boolean applied = jdbcTemplate.queryForObject("""
                    select applied_at is not null
                    from public.catalog_bootstrap_rule_sets
                    where rule_set_id = ?
                    for update
                    """, Boolean.class, RULE_SET_ID);
            if (applied == null) {
                throw new IllegalStateException("Authoritative catalog rule-set marker is invalid");
            }
            return applied;
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Missing authoritative catalog rule-set marker", exception);
        }
    }

    /**
     * Fresh base rows deliberately use explicit verified IDs. V11 reserves the
     * identity generator for catalog additions beginning at 122, so a rollback
     * here neither consumes nor corrupts the required base identity range.
     */
    static OffsetDateTime jdbcTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void initializeEmptyBaseCatalog(Map<Long, BaseCatalogItem> baseCatalog, OffsetDateTime databaseNow) {
        if (menuItems.count() != 0) {
            return;
        }

        Map<String, Integer> displayOrdersByCategory = new HashMap<>();
        for (BaseCatalogItem item : baseCatalog.values()) {
            int displayOrder = displayOrdersByCategory.merge(item.category(), 1, Integer::sum);
            jdbcTemplate.update("""
                    insert into public.menu_items (id, name, description, category, price_amount, pricing_mode,
                        active, available, standalone_orderable, display_order, created_at, updated_at, version)
                    values (?, ?, null, ?, ?, 'BASE_PLUS_ADJUSTMENTS', true, true, true, ?, ?, ?, 0)
                    """, item.id(), item.name(), item.category(), item.price(), displayOrder, databaseNow, databaseNow);
        }
    }

    private Map<Long, MenuItem> requireExpectedItems(Map<Long, BaseCatalogItem> baseCatalog) {
        Map<Long, MenuItem> result = new LinkedHashMap<>();
        for (BaseCatalogItem expected : baseCatalog.values()) {
            MenuItem item = menuItems.findById(expected.id()).orElseThrow(() -> new IllegalStateException(
                    "Missing verified base catalog item id " + expected.id() + " (" + expected.name() + ")"));
            if (!item.getName().equals(expected.name()) || !item.getCategory().equals(expected.category())) {
                throw new IllegalStateException("Verified base catalog item id " + expected.id()
                        + " has an unexpected identity");
            }
            if (REQUIRED_PRODUCT_IDS.contains(expected.id())) {
                result.put(expected.id(), item);
            }
        }
        return result;
    }

    private Map<Long, BaseCatalogItem> loadBaseCatalog() {
        try (InputStream input = new ClassPathResource("menu_sushi_mei.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            if (root == null || !root.isArray() || root.size() != 121) {
                throw new IllegalStateException("Verified base catalog must contain exactly 121 items");
            }

            Map<Long, BaseCatalogItem> items = new LinkedHashMap<>();
            for (int index = 0; index < root.size(); index++) {
                JsonNode source = root.get(index);
                long id = index + 1L;
                String name = requiredCatalogText(source, "producto", 160, id);
                String category = requiredCatalogText(source, "categoria", 120, id);
                BigDecimal price = requiredCatalogPrice(source, id);
                items.put(id, new BaseCatalogItem(id, name, category, price));
            }
            return items;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load the verified base catalog resource", exception);
        }
    }

    private String requiredCatalogText(JsonNode source, String field, int maximumLength, long id) {
        JsonNode value = source.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()
                || value.asText().length() > maximumLength) {
            throw new IllegalStateException("Invalid verified base catalog " + field + " for item id " + id);
        }
        return value.asText();
    }

    private BigDecimal requiredCatalogPrice(JsonNode source, long id) {
        JsonNode value = source.get("precio");
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("Invalid verified base catalog precio for item id " + id);
        }
        try {
            return checkoutMoney.normalizeNumericAmount(value.decimalValue());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid verified base catalog precio for item id " + id, exception);
        }
    }

    private Map<String, CatalogTag> synchronizeTags(Instant now) {
        Map<String, TagDefinition> definitions = Map.of(
                "ROLLO_CLASICO", new TagDefinition("Rollos clásicos", 10),
                "ROLLO_ESPECIAL", new TagDefinition("Rollos especiales", 20),
                "ROLLO_CAMARON", new TagDefinition("Rollos camarón", 30),
                "ROLLO_EZTRELLA", new TagDefinition("Rollos estrella", 40),
                "TOPPING", new TagDefinition("Toppings", 50));
        Map<String, CatalogTag> result = new HashMap<>();
        for (Map.Entry<String, TagDefinition> entry : definitions.entrySet()) {
            CatalogTag tag = tags.findByCode(entry.getKey()).orElseGet(() ->
                    tags.save(CatalogTag.create(entry.getKey(), entry.getValue().name(), entry.getValue().displayOrder(), now)));
            if (!tag.getName().equals(entry.getValue().name())
                    || !tag.isActive()
                    || tag.getDisplayOrder() != entry.getValue().displayOrder()) {
                tag.update(entry.getValue().name(), true, entry.getValue().displayOrder(), now);
            }
            result.put(entry.getKey(), tag);
        }
        return result;
    }

    private void synchronizeTagMembership(Map<String, CatalogTag> authoritativeTags,
                                          Instant now) {
        Map<String, Set<Long>> memberships = Map.of(
                "ROLLO_CLASICO", Set.of(18L, 24L, 49L, 80L, 107L),
                "ROLLO_ESPECIAL", Set.of(14L, 35L, 36L, 48L, 52L, 82L, 83L, 106L),
                "ROLLO_CAMARON", Set.of(13L, 17L, 23L, 47L, 68L, 79L),
                "ROLLO_EZTRELLA", Set.of(66L, 85L, 105L),
                "TOPPING", Set.of(53L, 74L, 108L));
        Set<CatalogTag> managedTags = new LinkedHashSet<>(authoritativeTags.values());
        for (MenuItem item : menuItems.findAll()) {
            Set<CatalogTag> desired = new LinkedHashSet<>();
            for (Map.Entry<String, Set<Long>> membership : memberships.entrySet()) {
                if (membership.getValue().contains(item.getId())) {
                    desired.add(authoritativeTags.get(membership.getKey()));
                }
            }
            item.synchronizeManagedTags(managedTags, desired, now);
        }
    }

    private void archiveDiscontinuedItems(Map<Long, MenuItem> items, Instant now) {
        for (long id : List.of(1L, 2L, 15L, 16L, 19L, 20L, 21L, 22L, 58L, 67L)) {
            MenuItem item = items.get(id);
            if (item.isActive() || item.isAvailable() || item.isStandaloneOrderable()) {
                item.archiveAsDiscontinued(now);
            }
        }
    }

    private void configureFixedCharolas(Map<Long, MenuItem> items,
                                        Map<String, CatalogTag> authoritativeTags,
                                        Instant now) {
        configureSingleTagGroup(items.get(37L), "Elige 3 rollos clásicos", 3, authoritativeTags.get("ROLLO_CLASICO"),
                SelectionPricingPolicy.INCLUDED, null, now);
        configureSingleTagGroup(items.get(50L), "Elige 3 rollos especiales", 3, authoritativeTags.get("ROLLO_ESPECIAL"),
                SelectionPricingPolicy.INCLUDED, null, now);
        configureSingleTagGroup(items.get(32L), "Elige 3 rollos camarón", 3, authoritativeTags.get("ROLLO_CAMARON"),
                SelectionPricingPolicy.INCLUDED, null, now);
        configureSingleTagGroup(items.get(38L), "Elige 5 rollos clásicos", 5, authoritativeTags.get("ROLLO_CLASICO"),
                SelectionPricingPolicy.INCLUDED, null, now);
        configureSingleTagGroup(items.get(51L), "Elige 5 rollos especiales", 5, authoritativeTags.get("ROLLO_ESPECIAL"),
                SelectionPricingPolicy.INCLUDED, null, now);
        configureSingleTagGroup(items.get(33L), "Elige 5 rollos camarón", 5, authoritativeTags.get("ROLLO_CAMARON"),
                SelectionPricingPolicy.INCLUDED, null, now);
    }

    private void configureBuildYourOwnCharolas(Map<String, CatalogTag> authoritativeTags, Instant now) {
        MenuItem familiar = findOrCreateContainer("Arma tu Charola Familiar", now);
        MenuItem supreme = findOrCreateContainer("Arma tu Charola Supreme", now);
        List<CatalogTag> rollTags = List.of(authoritativeTags.get("ROLLO_CLASICO"), authoritativeTags.get("ROLLO_ESPECIAL"),
                authoritativeTags.get("ROLLO_CAMARON"), authoritativeTags.get("ROLLO_EZTRELLA"));
        configureMultiTagGroup(familiar, "Elige 3 rollos", 3, rollTags, SelectionPricingPolicy.FULL_ITEM_PRICE, null, now);
        configureMultiTagGroup(supreme, "Elige 5 rollos", 5, rollTags, SelectionPricingPolicy.FULL_ITEM_PRICE, null, now);
    }

    private void configureSushiBoxes(Map<Long, MenuItem> items,
                                     Map<String, CatalogTag> authoritativeTags,
                                     Instant now) {
        configureSushiBox(items.get(96L), "Elige 2 rollos", "ROLLO_CLASICO", "79.00", authoritativeTags, now);
        configureSushiBox(items.get(97L), "Elige 2 rollos", "ROLLO_ESPECIAL", "89.00", authoritativeTags, now);
        configureSushiBox(items.get(95L), "Elige 2 rollos", "ROLLO_CAMARON", "99.00", authoritativeTags, now);

        MenuItem drinkPackage = findOrCreateDrinkPackage(now);
        for (long sushiBoxId : List.of(96L, 97L, 95L)) {
            MenuSelectionGroup extra = ensureGroup(items.get(sushiBoxId), "Agregar 2 bebidas", 0, 1, false, 10, now);
            synchronizeRules(extra, List.of(RuleDefinition.forItem(drinkPackage, SelectionPricingPolicy.FULL_ITEM_PRICE, null)), now);
        }
        MenuSelectionGroup drinks = ensureGroup(drinkPackage, "Elige 2 bebidas", 2, 2, true, 0, now);
        synchronizeRules(drinks, List.of(
                RuleDefinition.forItem(items.get(25L), SelectionPricingPolicy.INCLUDED, null),
                RuleDefinition.forItem(items.get(41L), SelectionPricingPolicy.INCLUDED, null),
                RuleDefinition.forItem(items.get(42L), SelectionPricingPolicy.INCLUDED, null)), now);
    }

    private void configureSushiBox(MenuItem sushiBox,
                                   String groupName,
                                   String includedTagCode,
                                   String referencePrice,
                                   Map<String, CatalogTag> authoritativeTags,
                                   Instant now) {
        MenuSelectionGroup rolls = ensureGroup(sushiBox, groupName, 2, 2, true, 0, now);
        List<RuleDefinition> rules = new ArrayList<>();
        for (String tagCode : List.of("ROLLO_CLASICO", "ROLLO_ESPECIAL", "ROLLO_CAMARON", "ROLLO_EZTRELLA")) {
            rules.add(RuleDefinition.forTag(authoritativeTags.get(tagCode),
                    tagCode.equals(includedTagCode) ? SelectionPricingPolicy.INCLUDED : SelectionPricingPolicy.PRICE_DIFFERENCE,
                    tagCode.equals(includedTagCode) ? null : new BigDecimal(referencePrice)));
        }
        synchronizeRules(rolls, rules, now);
    }

    private void configureSingleTagGroup(MenuItem parent,
                                         String name,
                                         int selectionCount,
                                         CatalogTag tag,
                                         SelectionPricingPolicy policy,
                                         BigDecimal referencePrice,
                                         Instant now) {
        configureMultiTagGroup(parent, name, selectionCount, List.of(tag), policy, referencePrice, now);
    }

    private void configureMultiTagGroup(MenuItem parent,
                                        String name,
                                        int selectionCount,
                                        List<CatalogTag> targetTags,
                                        SelectionPricingPolicy policy,
                                        BigDecimal referencePrice,
                                        Instant now) {
        MenuSelectionGroup group = ensureGroup(parent, name, selectionCount, selectionCount, true, 0, now);
        synchronizeRules(group, targetTags.stream()
                .map(tag -> RuleDefinition.forTag(tag, policy, referencePrice))
                .toList(), now);
    }

    private MenuItem findOrCreateContainer(String name, Instant now) {
        List<MenuItem> matches = menuItems.findAll().stream().filter(item -> item.getName().equals(name)).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous authoritative container item: " + name);
        }
        MenuItem item = matches.isEmpty()
                ? menuItems.save(MenuItem.create(name, null, CATEGORY, BigDecimal.ZERO.setScale(2),
                MenuItemPricingMode.SELECTION_SUM, true, true, 0, now))
                : matches.get(0);
        item.synchronizeAuthoritativeState(BigDecimal.ZERO.setScale(2), MenuItemPricingMode.SELECTION_SUM,
                true, true, true, now);
        return item;
    }

    private MenuItem findOrCreateDrinkPackage(Instant now) {
        List<MenuItem> matches = menuItems.findAll().stream().filter(item -> item.getName().equals(DRINK_PACKAGE_NAME)).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous authoritative drink package item");
        }
        MenuItem item = matches.isEmpty()
                ? menuItems.save(MenuItem.create(DRINK_PACKAGE_NAME, null, CATEGORY, new BigDecimal("39.00"),
                MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS, true, false, 0, now))
                : matches.get(0);
        item.synchronizeAuthoritativeState(new BigDecimal("39.00"), MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS,
                true, true, false, now);
        return item;
    }

    private MenuSelectionGroup ensureGroup(MenuItem parent,
                                           String name,
                                           int minSelections,
                                           int maxSelections,
                                           boolean allowDuplicates,
                                           int displayOrder,
                                           Instant now) {
        List<MenuSelectionGroup> matches = groups.findByParentMenuItemIdOrderByDisplayOrderAscIdAsc(parent.getId()).stream()
                .filter(group -> group.getName().equals(name)).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous authoritative selection group " + name + " for " + parent.getName());
        }
        MenuSelectionGroup group = matches.isEmpty()
                ? groups.save(MenuSelectionGroup.create(parent, name, minSelections, maxSelections, allowDuplicates, displayOrder, now))
                : matches.get(0);
        if (!group.getName().equals(name)
                || group.getMinSelections() != minSelections
                || group.getMaxSelections() != maxSelections
                || group.isAllowDuplicates() != allowDuplicates
                || group.getDisplayOrder() != displayOrder
                || !group.isActive()) {
            group.update(name, minSelections, maxSelections, allowDuplicates, displayOrder, true, now);
        }
        return group;
    }

    private void synchronizeRules(MenuSelectionGroup group, List<RuleDefinition> expectedRules, Instant now) {
        List<MenuSelectionRule> existing = rules.findBySelectionGroupIdOrderByPriorityDescIdAsc(group.getId());
        Set<RuleKey> expectedKeys = expectedRules.stream().map(RuleDefinition::key).collect(java.util.stream.Collectors.toSet());
        for (MenuSelectionRule rule : existing) {
            if (rule.isActive() && !expectedKeys.contains(RuleKey.from(rule))) {
                rule.archive(now);
            }
        }
        for (RuleDefinition expected : expectedRules) {
            List<MenuSelectionRule> matches = existing.stream().filter(MenuSelectionRule::isActive)
                    .filter(rule -> RuleKey.from(rule).equals(expected.key())).toList();
            if (matches.size() > 1) {
                throw new IllegalStateException("Ambiguous authoritative selection rule in group " + group.getName());
            }
            MenuSelectionRule rule = matches.isEmpty()
                    ? rules.save(MenuSelectionRule.create(group, expected.menuItem(), expected.tag(), expected.policy(),
                    expected.referencePrice(), null, 0, now))
                    : matches.get(0);
            if (!sameMenuItem(rule.getTargetMenuItem(), expected.menuItem())
                    || !sameTag(rule.getTargetTag(), expected.tag())
                    || rule.getPricingPolicy() != expected.policy()
                    || !Objects.equals(rule.getReferencePriceAmount(), expected.referencePrice())
                    || rule.getFixedSurchargeAmount() != null
                    || rule.getPriority() != 0
                    || !rule.isActive()) {
                rule.update(expected.menuItem(), expected.tag(), expected.policy(), expected.referencePrice(), null, 0, true, now);
            }
        }
    }

    private boolean sameMenuItem(MenuItem left, MenuItem right) {
        return left == null ? right == null : right != null && Objects.equals(left.getId(), right.getId());
    }

    private boolean sameTag(CatalogTag left, CatalogTag right) {
        return left == null ? right == null : right != null && Objects.equals(left.getId(), right.getId());
    }

    private record BaseCatalogItem(long id, String name, String category, BigDecimal price) {
    }

    private record TagDefinition(String name, int displayOrder) {
    }

    private record RuleDefinition(MenuItem menuItem,
                                  CatalogTag tag,
                                  SelectionPricingPolicy policy,
                                  BigDecimal referencePrice) {
        static RuleDefinition forItem(MenuItem item, SelectionPricingPolicy policy, BigDecimal referencePrice) {
            return new RuleDefinition(Objects.requireNonNull(item), null, policy, referencePrice);
        }

        static RuleDefinition forTag(CatalogTag tag, SelectionPricingPolicy policy, BigDecimal referencePrice) {
            return new RuleDefinition(null, Objects.requireNonNull(tag), policy, referencePrice);
        }

        RuleKey key() {
            return new RuleKey(menuItem == null ? null : menuItem.getId(), tag == null ? null : tag.getId());
        }
    }

    private record RuleKey(Long menuItemId, Long tagId) {
        static RuleKey from(MenuSelectionRule rule) {
            return new RuleKey(rule.getTargetMenuItem() == null ? null : rule.getTargetMenuItem().getId(),
                    rule.getTargetTag() == null ? null : rule.getTargetTag().getId());
        }
    }
}
