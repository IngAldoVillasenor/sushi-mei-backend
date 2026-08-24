package com.sushimei.sushimei.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        AuthoritativeCatalogRulesIntegrationTest.TestInfrastructureConfiguration.class})
class AuthoritativeCatalogRulesIntegrationTest {

    @Autowired
    private CatalogConfigurationService catalogConfigurationService;

    @Autowired
    private MenuCatalogService menuCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthoritativeCatalogRulesService rulesService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void directJdbcTimestampBindingUsesUtcOffsetDateTimeRatherThanInstant() {
        Instant instant = Instant.parse("2026-08-13T18:45:12.345Z");

        Object databaseTimestamp = AuthoritativeCatalogRulesService.jdbcTimestamp(instant);

        assertThat(databaseTimestamp).isInstanceOf(OffsetDateTime.class);
        assertThat(databaseTimestamp).isEqualTo(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    @Test
    void cleanBootstrapSeedsTheVerified121ItemCatalogAndCreatesOnlyThreePhaseItems() throws IOException {
        JsonNode source = verifiedBaseCatalog();

        assertThat(source).hasSize(121);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isEqualTo(124);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items where id between 1 and 121", Integer.class))
                .isEqualTo(121);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items where id between 1 and 121 and description is not null", Integer.class))
                .isEqualTo(23);

        Map<String, Integer> expectedDisplayOrders = new HashMap<>();
        for (int index = 0; index < source.size(); index++) {
            long id = index + 1L;
            JsonNode expected = source.get(index);
            assertThat(jdbcTemplate.queryForObject("select name from public.menu_items where id = ?", String.class, id))
                    .isEqualTo(expected.required("producto").asText());
            assertThat(jdbcTemplate.queryForObject("select category from public.menu_items where id = ?", String.class, id))
                    .isEqualTo(expected.required("categoria").asText());
            assertThat(jdbcTemplate.queryForObject("select price_amount from public.menu_items where id = ?", BigDecimal.class, id))
                    .isEqualByComparingTo(expected.required("precio").decimalValue());
            int expectedDisplayOrder = expectedDisplayOrders.merge(expected.required("categoria").asText(), 1, Integer::sum);
            assertThat(jdbcTemplate.queryForObject("select display_order from public.menu_items where id = ?", Integer.class, id))
                    .isEqualTo(expectedDisplayOrder);
        }

        assertThat(jdbcTemplate.queryForObject("select name from public.menu_items where id = 109", String.class))
                .isEqualTo("Vaso hielo");
        assertThat(jdbcTemplate.queryForObject("select name from public.menu_items where id = 110", String.class))
                .isEqualTo(source.get(109).required("producto").asText());
        assertThat(jdbcTemplate.queryForObject("select name from public.menu_items where id = 111", String.class))
                .isEqualTo(source.get(110).required("producto").asText());
        List<Long> phaseCreatedIds = jdbcTemplate.queryForList("""
                        select id from public.menu_items
                        where name in ('Arma tu Charola Familiar', 'Arma tu Charola Supreme', 'Paquete 2 bebidas Sushi Box')
                        """, Long.class);
        assertThat(phaseCreatedIds)
                .hasSize(3)
                .allSatisfy(id -> assertThat(id).isGreaterThan(121L));
        MenuItemResponse ordinaryCreatedAfterBootstrap = menuCatalogService.create(new CreateMenuItemRequest(
                "Identity generation probe", null, "Pruebas", new BigDecimal("1.00"), true, true, 0));
        assertThat(ordinaryCreatedAfterBootstrap.id())
                .isGreaterThan(phaseCreatedIds.stream().mapToLong(Long::longValue).max().orElseThrow());
        jdbcTemplate.update("delete from public.menu_items where id = ?", ordinaryCreatedAfterBootstrap.id());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.catalog_bootstrap_rule_sets
                where rule_set_id = ? and applied_at is not null
                """, Integer.class, AuthoritativeCatalogRulesService.RULE_SET_ID)).isEqualTo(1);
    }

    @Test
    void successfulMarkerPreventsLaterCatalogEditsFromBeingRewrittenOnRestart() {
        MenuItemResponse california = menuCatalogService.get(24L);
        MenuItemResponse changed = menuCatalogService.update(california.id(), new UpdateMenuItemRequest(
                california.name(), california.description(), california.category(), new BigDecimal("80.00"),
                california.active(), california.available(), california.standaloneOrderable(), california.displayOrder(),
                california.version(), california.pricingMode()));

        rulesService.synchronize();

        assertThat(menuCatalogService.get(24L).price()).isEqualByComparingTo("80.00");
        menuCatalogService.update(changed.id(), new UpdateMenuItemRequest(
                changed.name(), changed.description(), changed.category(), new BigDecimal("79.00"),
                changed.active(), changed.available(), changed.standaloneOrderable(), changed.displayOrder(),
                changed.version(), changed.pricingMode()));
    }

    @Test
    void failedRuleSynchronizationAfterBaseInitializationDoesNotMarkTheRuleSetAppliedAndCanBeRetried() {
        jdbcTemplate.update("update public.catalog_bootstrap_rule_sets set applied_at = null where rule_set_id = ?",
                AuthoritativeCatalogRulesService.RULE_SET_ID);
        MenuItemResponse duplicateContainer = menuCatalogService.create(new CreateMenuItemRequest(
                "Arma tu Charola Familiar", null, "Charolas/Sushi Box", new BigDecimal("1.00"), true, true, 0));

        assertThatThrownBy(rulesService::synchronize).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.catalog_bootstrap_rule_sets
                where rule_set_id = ? and applied_at is null
                """, Integer.class, AuthoritativeCatalogRulesService.RULE_SET_ID)).isEqualTo(1);

        jdbcTemplate.update("delete from public.menu_items where id = ?", duplicateContainer.id());
        rulesService.synchronize();

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isEqualTo(124);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items where id = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items where name = 'Arma tu Charola Familiar'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.catalog_bootstrap_rule_sets
                where rule_set_id = ? and applied_at is not null
                """, Integer.class, AuthoritativeCatalogRulesService.RULE_SET_ID)).isEqualTo(1);
    }

    @Test
    @DirtiesContext
    void incompleteExistingBaseCatalogIsRejectedEvenWhenMissingItemIsNotAPhasePrerequisite() {
        jdbcTemplate.update("update public.catalog_bootstrap_rule_sets set applied_at = null where rule_set_id = ?",
                AuthoritativeCatalogRulesService.RULE_SET_ID);
        jdbcTemplate.update("delete from public.menu_items where id = 10");

        assertThatThrownBy(rulesService::synchronize).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.catalog_bootstrap_rule_sets
                where rule_set_id = ? and applied_at is null
                """, Integer.class, AuthoritativeCatalogRulesService.RULE_SET_ID)).isEqualTo(1);
    }

    @Test
    void bootstrapCreatesOnlyTheApprovedAuthoritativeTagMemberships() {
        Map<String, Set<Long>> expected = Map.of(
                "ROLLO_CLASICO", Set.of(18L, 24L, 49L, 80L, 107L),
                "ROLLO_ESPECIAL", Set.of(14L, 35L, 36L, 48L, 52L, 82L, 83L, 106L),
                "ROLLO_CAMARON", Set.of(13L, 17L, 23L, 47L, 68L, 79L),
                "ROLLO_EZTRELLA", Set.of(66L, 85L, 105L),
                "TOPPING", Set.of(53L, 74L, 108L));

        Map<String, Set<Long>> actual = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        select tag.code, membership.menu_item_id
                        from public.catalog_tags tag
                        join public.menu_item_tags membership on membership.tag_id = tag.id
                        where tag.code in ('ROLLO_CLASICO', 'ROLLO_ESPECIAL', 'ROLLO_CAMARON', 'ROLLO_EZTRELLA', 'TOPPING')
                        order by tag.code, membership.menu_item_id
                        """,
                resultSet -> {
                    String code = resultSet.getString(1);
                    actual.computeIfAbsent(code, ignored -> new java.util.LinkedHashSet<>()).add(resultSet.getLong(2));
                });

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void fixedCharolasEnforceTheirExactRollCountCategoryAndDuplicatePolicy() {
        MenuSelectionGroupConfigurationResponse familiar = onlyGroup(37L);
        MenuItemQuoteResponse validFamiliar = quote(37L, group(familiar.id(), selection(24L, 3)));
        assertThat(validFamiliar.total()).isEqualByComparingTo("199.00");

        assertCatalogError(37L, List.of(group(familiar.id(), selection(24L, 2))),
                CatalogDomainError.MENU_CONFIGURATION_INCOMPLETE);
        assertCatalogError(37L, List.of(group(familiar.id(), selection(66L, 3))),
                CatalogDomainError.MENU_SELECTION_NOT_ALLOWED);

        MenuSelectionGroupConfigurationResponse supreme = onlyGroup(38L);
        MenuItemQuoteResponse validSupreme = quote(38L, group(supreme.id(), selection(24L, 5)));
        assertThat(validSupreme.total()).isEqualByComparingTo("339.00");
        assertCatalogError(38L, List.of(group(supreme.id(), selection(24L, 4))),
                CatalogDomainError.MENU_CONFIGURATION_INCOMPLETE);
    }

    @Test
    void buildYourOwnCharolasUseSelectionSumWithoutArtificialRootPrice() {
        MenuItemResponse familiar = itemByName("Arma tu Charola Familiar");
        MenuSelectionGroupConfigurationResponse familiarGroup = onlyGroup(familiar.id());
        MenuItemQuoteResponse familiarQuote = quote(familiar.id(), group(familiarGroup.id(),
                selection(24L, 1), selection(66L, 1), selection(79L, 1)));

        assertThat(familiar.pricingMode()).isEqualTo(MenuItemPricingMode.SELECTION_SUM);
        assertThat(familiar.price()).isEqualByComparingTo("0.00");
        assertThat(familiarQuote.baseUnitPrice()).isEqualByComparingTo("0.00");
        assertThat(familiarQuote.unitTotal()).isEqualByComparingTo("287.00");
        assertThat(familiarQuote.total()).isEqualByComparingTo("287.00");

        MenuItemResponse supreme = itemByName("Arma tu Charola Supreme");
        MenuItemQuoteResponse supremeQuote = quote(supreme.id(), group(onlyGroup(supreme.id()).id(), selection(24L, 5)));
        assertThat(supremeQuote.total()).isEqualByComparingTo("395.00");

        MenuItemQuoteResponse ordinaryRoll = quote(24L);
        assertThat(ordinaryRoll.baseUnitPrice()).isEqualByComparingTo("79.00");
        assertThat(ordinaryRoll.total()).isEqualByComparingTo("79.00");
    }

    @Test
    void sushiBoxesUseReviewedReferencePricesWithoutReducingTheirBase() {
        MenuItemQuoteResponse classic = quote(96L, group(onlyRequiredRollGroup(96L).id(),
                selection(24L, 1), selection(79L, 1)));
        assertThat(classic.total()).isEqualByComparingTo("319.00");

        MenuItemQuoteResponse special = quote(97L, group(onlyRequiredRollGroup(97L).id(),
                selection(35L, 1), selection(24L, 1)));
        assertThat(special.total()).isEqualByComparingTo("319.00");

        MenuItemQuoteResponse shrimp = quote(95L, group(onlyRequiredRollGroup(95L).id(),
                selection(79L, 1), selection(24L, 1)));
        assertThat(shrimp.total()).isEqualByComparingTo("339.00");
    }

    @Test
    void sushiBoxDrinkExtraChargesOnePairAndOnlyAllowsTheReviewedDrinks() {
        MenuItemConfigurationResponse boxConfiguration = catalogConfigurationService.operationalConfiguration(96L);
        MenuSelectionGroupConfigurationResponse rollGroup = requiredGroup(boxConfiguration);
        MenuSelectionGroupConfigurationResponse extraGroup = optionalDrinkExtraGroup(boxConfiguration);
        Long packageId = extraGroup.options().get(0).menuItemId();
        MenuSelectionGroupConfigurationResponse drinks = onlyGroup(packageId);
        MenuItemResponse drinkPackage = menuCatalogService.get(packageId);
        assertThat(drinkPackage.active()).isTrue();
        assertThat(drinkPackage.available()).isTrue();
        assertThat(drinkPackage.standaloneOrderable()).isFalse();
        assertThat(drinkPackage.price()).isEqualByComparingTo("39.00");

        MenuItemQuoteResponse withoutExtra = quote(96L, group(rollGroup.id(), selection(24L, 2)));
        assertThat(withoutExtra.total()).isEqualByComparingTo("299.00");

        MenuItemQuoteResponse mixedPair = quote(96L,
                group(rollGroup.id(), selection(24L, 2)),
                group(extraGroup.id(), selection(packageId, 1, group(drinks.id(), selection(25L, 1), selection(41L, 1)))));
        assertThat(mixedPair.total()).isEqualByComparingTo("338.00");
        MenuQuoteSelectionResponse packageSelection = mixedPair.groups().stream()
                .flatMap(group -> group.selections().stream())
                .filter(selection -> selection.menuItemId().equals(packageId))
                .findFirst().orElseThrow();
        assertThat(packageSelection.displayOnTicket()).isFalse();
        assertThat(packageSelection.groups()).flatMap(MenuQuoteGroupResponse::selections)
                .allSatisfy(selection -> assertThat(selection.displayOnTicket()).isTrue());

        MenuItemQuoteResponse duplicatePair = quote(96L,
                group(rollGroup.id(), selection(24L, 2)),
                group(extraGroup.id(), selection(packageId, 1, group(drinks.id(), selection(25L, 2)))));
        assertThat(duplicatePair.total()).isEqualByComparingTo("338.00");

        assertCatalogError(96L, List.of(
                        group(rollGroup.id(), selection(24L, 2)),
                        group(extraGroup.id(), selection(packageId, 1, group(drinks.id(), selection(25L, 1))))),
                CatalogDomainError.MENU_CONFIGURATION_INCOMPLETE);
        assertCatalogError(96L, List.of(
                        group(rollGroup.id(), selection(24L, 2)),
                        group(extraGroup.id(), selection(packageId, 1, group(drinks.id(), selection(25L, 3))))),
                CatalogDomainError.MENU_CONFIGURATION_INVALID);

        for (long excludedCalpiId : List.of(26L, 27L, 28L)) {
            assertCatalogError(96L, List.of(
                            group(rollGroup.id(), selection(24L, 2)),
                            group(extraGroup.id(), selection(packageId, 1,
                                    group(drinks.id(), selection(excludedCalpiId, 1), selection(25L, 1))))),
                    CatalogDomainError.MENU_SELECTION_NOT_ALLOWED);
        }
    }

    @Test
    void archivedItemsAndRequiresConfigurationSignalAreAuthoritative() {
        for (long id : List.of(1L, 2L, 15L, 16L, 19L, 20L, 21L, 22L, 58L, 67L)) {
            MenuItemResponse archived = menuCatalogService.get(id);
            assertThat(archived.active()).isFalse();
            assertThat(archived.available()).isFalse();
            assertThat(archived.standaloneOrderable()).isFalse();
        }

        assertThat(menuCatalogService.get(25L).requiresConfiguration()).isFalse();
        assertThat(menuCatalogService.get(37L).requiresConfiguration()).isTrue();
        assertThat(itemByName("Arma tu Charola Familiar").requiresConfiguration()).isTrue();
        assertThat(menuCatalogService.get(96L).requiresConfiguration()).isTrue();
    }

    private MenuItemResponse itemByName(String name) {
        return menuCatalogService.list(true, false).stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private MenuSelectionGroupConfigurationResponse onlyGroup(Long itemId) {
        List<MenuSelectionGroupConfigurationResponse> groups = catalogConfigurationService.operationalConfiguration(itemId).groups();
        assertThat(groups).hasSize(1);
        return groups.get(0);
    }

    private MenuSelectionGroupConfigurationResponse onlyRequiredRollGroup(Long itemId) {
        return requiredGroup(catalogConfigurationService.operationalConfiguration(itemId));
    }

    private MenuSelectionGroupConfigurationResponse requiredGroup(MenuItemConfigurationResponse configuration) {
        return configuration.groups().stream()
                .filter(group -> group.minSelections() > 0)
                .findFirst()
                .orElseThrow();
    }

    private MenuSelectionGroupConfigurationResponse optionalDrinkExtraGroup(MenuItemConfigurationResponse configuration) {
        return configuration.groups().stream()
                .filter(group -> group.minSelections() == 0 && group.maxSelections() == 1)
                .findFirst()
                .orElseThrow();
    }

    private MenuItemQuoteResponse quote(Long itemId, MenuQuoteGroupRequest... groups) {
        return quote(itemId, List.of(groups));
    }

    private MenuItemQuoteResponse quote(Long itemId, List<MenuQuoteGroupRequest> groups) {
        return catalogConfigurationService.quote(itemId, new MenuItemQuoteRequest(1, groups));
    }

    private MenuQuoteGroupRequest group(Long groupId, MenuQuoteSelectionRequest... selections) {
        return new MenuQuoteGroupRequest(groupId, List.of(selections));
    }

    private MenuQuoteSelectionRequest selection(Long itemId, int quantity, MenuQuoteGroupRequest... nestedGroups) {
        return new MenuQuoteSelectionRequest(itemId, quantity, List.of(nestedGroups));
    }

    private void assertCatalogError(Long itemId,
                                    List<MenuQuoteGroupRequest> groups,
                                    CatalogDomainError expectedError) {
        assertThatThrownBy(() -> quote(itemId, groups))
                .isInstanceOf(CatalogConfigurationException.class)
                .extracting(exception -> ((CatalogConfigurationException) exception).getError())
                .isEqualTo(expectedError);
    }

    private JsonNode verifiedBaseCatalog() throws IOException {
        try (InputStream input = new ClassPathResource("menu_sushi_mei.json").getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

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
}
