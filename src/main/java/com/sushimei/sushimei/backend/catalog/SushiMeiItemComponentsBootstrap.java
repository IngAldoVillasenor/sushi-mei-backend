package com.sushimei.sushimei.backend.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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

/** Applies the reviewed Sushi Mei default-component catalog exactly once. */
@Component
@Order(150)
class SushiMeiItemComponentsBootstrap implements ApplicationRunner {

    private final SushiMeiItemComponentsService componentsService;

    SushiMeiItemComponentsBootstrap(SushiMeiItemComponentsService componentsService) {
        this.componentsService = componentsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        componentsService.synchronize();
    }
}

@Service
class SushiMeiItemComponentsService {

    static final String RULE_SET_ID = "SUSHIMEI_ITEM_COMPONENTS_V1";
    private static final String RESOURCE_PATH = "catalog/sushimei-item-components-v1.json";

    private final MenuCatalogRepository menuItemRepository;
    private final MenuItemDefaultComponentRepository componentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    SushiMeiItemComponentsService(MenuCatalogRepository menuItemRepository,
                                  MenuItemDefaultComponentRepository componentRepository,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.menuItemRepository = Objects.requireNonNull(menuItemRepository, "menuItemRepository must not be null");
        this.componentRepository = Objects.requireNonNull(componentRepository, "componentRepository must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public void synchronize() {
        if (isAppliedWithLock()) {
            return;
        }

        Instant now = clock.instant();
        List<ItemComponentsDefinition> definitions = loadDefinitions();
        validateDefinitions(definitions);
        for (ItemComponentsDefinition definition : definitions) {
            MenuItem item = menuItemRepository.findById(definition.menuItemId())
                    .orElseThrow(() -> new IllegalStateException("Missing reviewed menu item " + definition.menuItemId()));
            if (!definition.expectedName().equals(item.getName())) {
                throw new IllegalStateException("Reviewed component menu identity mismatch for item " + definition.menuItemId());
            }
            item.update(item.getName(), definition.description(), item.getCategory(), item.getPriceAmount(),
                    item.getPricingMode(), item.isActive(), item.isAvailable(), item.isStandaloneOrderable(),
                    item.getDisplayOrder(), now);
            for (ComponentDefinition component : definition.components()) {
                componentRepository.save(MenuItemDefaultComponent.create(item, component.code(), component.displayName(),
                        component.detail(), component.includedByDefault(), component.removable(), component.displayOrder()));
            }
        }
        menuItemRepository.flush();
        componentRepository.flush();
        markApplied(now);
    }

    private boolean isAppliedWithLock() {
        try {
            Boolean applied = jdbcTemplate.queryForObject("""
                    select applied_at is not null
                    from public.catalog_bootstrap_rule_sets
                    where rule_set_id = ?
                    for update
                    """, Boolean.class, RULE_SET_ID);
            if (applied == null) {
                throw new IllegalStateException("Sushi Mei item-component rule-set marker is invalid");
            }
            return applied;
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Missing Sushi Mei item-component rule-set marker", exception);
        }
    }

    private void markApplied(Instant now) {
        int marked = jdbcTemplate.update("""
                update public.catalog_bootstrap_rule_sets
                set applied_at = ?
                where rule_set_id = ? and applied_at is null
                """, now.atOffset(ZoneOffset.UTC), RULE_SET_ID);
        if (marked != 1) {
            throw new IllegalStateException("Sushi Mei item-component rule set could not be marked as applied");
        }
    }

    private List<ItemComponentsDefinition> loadDefinitions() {
        try (InputStream input = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() { });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load reviewed Sushi Mei item-component catalog", exception);
        }
    }

    private static void validateDefinitions(List<ItemComponentsDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalStateException("Reviewed Sushi Mei item-component catalog is empty");
        }
        Set<Long> itemIds = new HashSet<>();
        for (ItemComponentsDefinition definition : definitions) {
            if (definition == null || definition.menuItemId() == null || definition.menuItemId() <= 0
                    || blank(definition.expectedName()) || blank(definition.description())
                    || definition.components() == null || definition.components().isEmpty()
                    || !itemIds.add(definition.menuItemId())) {
                throw new IllegalStateException("Reviewed Sushi Mei item-component catalog is invalid");
            }
            Set<String> codes = new HashSet<>();
            Set<Integer> displayOrders = new HashSet<>();
            for (ComponentDefinition component : definition.components()) {
                if (component == null || blank(component.code()) || blank(component.displayName())
                        || component.displayOrder() < 0 || !codes.add(component.code())
                        || !displayOrders.add(component.displayOrder())) {
                    throw new IllegalStateException("Reviewed component definition is invalid for item "
                            + definition.menuItemId());
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    record ItemComponentsDefinition(Long menuItemId,
                                    String expectedName,
                                    String description,
                                    List<ComponentDefinition> components) {
    }

    record ComponentDefinition(String code,
                               String displayName,
                               String detail,
                               boolean includedByDefault,
                               boolean removable,
                               int displayOrder) {
    }
}
