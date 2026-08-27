package com.sushimei.sushimei.backend.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies reviewed optional selection data without embedding it in reusable configuration services. */
@Component
@Order(175)
class SushiMeiOptionalSelectionGroupsBootstrap implements ApplicationRunner {
    private final SushiMeiOptionalSelectionGroupsService service;
    SushiMeiOptionalSelectionGroupsBootstrap(SushiMeiOptionalSelectionGroupsService service) { this.service = service; }
    @Override public void run(ApplicationArguments args) { service.synchronize(); }
}

@Service
class SushiMeiOptionalSelectionGroupsService {
    static final String RULE_SET_ID = "SUSHIMEI_OPTIONAL_TOPPINGS_V1";
    private static final String RESOURCE_PATH = "catalog/sushimei-optional-selection-groups-v1.json";
    private static final String TARGET_TAG_CODE = "TOPPING";
    private static final String GROUP_NAME = "Toppings";

    private final MenuCatalogRepository menuItemRepository;
    private final CatalogTagRepository tagRepository;
    private final MenuSelectionGroupRepository groupRepository;
    private final MenuSelectionRuleRepository ruleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    SushiMeiOptionalSelectionGroupsService(MenuCatalogRepository menuItemRepository,
                                           CatalogTagRepository tagRepository,
                                           MenuSelectionGroupRepository groupRepository,
                                           MenuSelectionRuleRepository ruleRepository,
                                           JdbcTemplate jdbcTemplate,
                                           ObjectMapper objectMapper,
                                           Clock clock) {
        this.menuItemRepository = Objects.requireNonNull(menuItemRepository);
        this.tagRepository = Objects.requireNonNull(tagRepository);
        this.groupRepository = Objects.requireNonNull(groupRepository);
        this.ruleRepository = Objects.requireNonNull(ruleRepository);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void synchronize() {
        if (isAppliedWithLock()) return;
        CatalogTag tag = tagRepository.findByCode(TARGET_TAG_CODE)
                .filter(CatalogTag::isActive)
                .orElseThrow(() -> new IllegalStateException("Missing reviewed optional-selection tag"));
        Instant now = clock.instant();
        List<ItemDefinition> definitions = loadDefinitions();
        Set<Long> ids = new HashSet<>();
        for (ItemDefinition definition : definitions) {
            if (definition == null || definition.menuItemId() == null || definition.menuItemId() <= 0
                    || definition.expectedName() == null || definition.expectedName().isBlank()
                    || !ids.add(definition.menuItemId())) {
                throw new IllegalStateException("Reviewed optional-selection catalog is invalid");
            }
            MenuItem item = menuItemRepository.findById(definition.menuItemId())
                    .orElseThrow(() -> new IllegalStateException("Missing reviewed menu item " + definition.menuItemId()));
            if (!definition.expectedName().equals(item.getName())) {
                throw new IllegalStateException("Reviewed optional-selection identity mismatch for item " + definition.menuItemId());
            }
            MenuSelectionGroup group = synchronizeGroup(item, now);
            synchronizeTagRule(group, tag, now);
        }
        groupRepository.flush();
        ruleRepository.flush();
        markApplied(now);
    }

    private MenuSelectionGroup synchronizeGroup(MenuItem item, Instant now) {
        List<MenuSelectionGroup> matchingGroups = groupRepository
                .findByParentMenuItemIdAndNameOrderByIdAsc(item.getId(), GROUP_NAME);
        if (matchingGroups.size() > 1) {
            throw new IllegalStateException("Ambiguous reviewed optional-selection group for item " + item.getId());
        }
        MenuSelectionGroup group = matchingGroups.isEmpty()
                ? MenuSelectionGroup.create(item, GROUP_NAME, 0, 1, false, 100, now)
                : matchingGroups.get(0);
        group.update(GROUP_NAME, 0, 1, false, 100, true, now);
        return groupRepository.save(group);
    }

    private void synchronizeTagRule(MenuSelectionGroup group, CatalogTag tag, Instant now) {
        List<MenuSelectionRule> matchingRules = ruleRepository.findBySelectionGroupIdOrderByPriorityDescIdAsc(group.getId())
                .stream()
                .filter(rule -> rule.getTargetTag() != null && rule.getTargetTag().getId().equals(tag.getId()))
                .toList();
        if (matchingRules.size() > 1) {
            throw new IllegalStateException("Ambiguous reviewed optional-selection rule for group " + group.getId());
        }
        MenuSelectionRule rule = matchingRules.isEmpty()
                ? MenuSelectionRule.create(group, null, tag, SelectionPricingPolicy.FULL_ITEM_PRICE, null, null, 0, now)
                : matchingRules.get(0);
        rule.update(null, tag, SelectionPricingPolicy.FULL_ITEM_PRICE, null, null, 0, true, now);
        ruleRepository.save(rule);
    }

    private boolean isAppliedWithLock() {
        try {
            Boolean applied = jdbcTemplate.queryForObject("""
                    select applied_at is not null from public.catalog_bootstrap_rule_sets
                    where rule_set_id = ? for update
                    """, Boolean.class, RULE_SET_ID);
            if (applied == null) throw new IllegalStateException("Optional-selection rule-set marker is invalid");
            return applied;
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Missing optional-selection rule-set marker", exception);
        }
    }

    private void markApplied(Instant now) {
        if (jdbcTemplate.update("""
                update public.catalog_bootstrap_rule_sets set applied_at = ?
                where rule_set_id = ? and applied_at is null
                """, now.atOffset(ZoneOffset.UTC), RULE_SET_ID) != 1) {
            throw new IllegalStateException("Optional-selection rule set could not be marked applied");
        }
    }

    private List<ItemDefinition> loadDefinitions() {
        try (InputStream input = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            List<ItemDefinition> definitions = objectMapper.readValue(input, new TypeReference<>() { });
            if (definitions == null || definitions.size() != 22) {
                throw new IllegalStateException("Reviewed optional-selection catalog must contain exactly 22 items");
            }
            return definitions;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load reviewed optional-selection catalog", exception);
        }
    }

    record ItemDefinition(Long menuItemId, String expectedName) { }
}
