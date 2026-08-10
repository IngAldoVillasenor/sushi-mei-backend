package com.sushimei.sushimei.backend.catalog;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import(CatalogConfigurationServiceIntegrationTest.TestInfrastructureConfiguration.class)
class CatalogConfigurationServiceIntegrationTest {

    @Autowired private MenuCatalogService menuCatalogService;
    @Autowired private CatalogConfigurationService catalogConfigurationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void cleanCatalogTables() {
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
        entityManager.clear();
    }

    @Test
    void sushiBoxPriceDifferenceUsesExactDataDrivenReferencePrice() {
        MenuItemResponse sushiBox = createItem("Sushi Box Clásica", "Cajas", "250.00", true);
        MenuItemResponse classic = createItem("California", "Rollos Clásicos", "79.00", true);
        MenuItemResponse shrimp = createItem("Camarón", "Rollos Camarón", "99.00", true);
        CatalogTagResponse roll = createTag("roll", "Rollos");
        assignTags(classic, roll);
        assignTags(shrimp, roll);
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(sushiBox.id(),
                new CreateMenuSelectionGroupRequest("Elige tus rollos", 2, 2, true, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.TAG, roll.id(), SelectionPricingPolicy.PRICE_DIFFERENCE,
                new BigDecimal("79.00"), null, 10));

        MenuItemQuoteResponse quote = catalogConfigurationService.quote(sushiBox.id(), new MenuItemQuoteRequest(1,
                List.of(groupRequest(group.id(), selection(classic.id(), 1), selection(shrimp.id(), 1)))));

        assertThat(quote.baseUnitPrice()).isEqualByComparingTo("250.00");
        assertThat(quote.unitAdjustmentTotal()).isEqualByComparingTo("20.00");
        assertThat(quote.unitTotal()).isEqualByComparingTo("270.00");
        assertThat(quote.total()).isEqualByComparingTo("270.00");
    }

    @Test
    void policiesAndPriorityAreResolvedFromRulesRatherThanProductCode() {
        MenuItemResponse root = createItem("Promoción", "Combos", "100.00", true);
        MenuItemResponse selected = createItem("Selección", "Rollos", "80.00", true);
        CatalogTagResponse generic = createTag("roll", "Rollos");
        MenuItemResponse tagged = assignTags(selected, generic);
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(root.id(),
                new CreateMenuSelectionGroupRequest("Elige uno", 1, 1, false, 0));
        MenuSelectionRuleResponse broad = catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.TAG, generic.id(), SelectionPricingPolicy.FULL_ITEM_PRICE, null, null, 10));
        MenuSelectionRuleResponse exact = catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, tagged.id(), SelectionPricingPolicy.INCLUDED, null, null, 100));
        assertThat(quoteOne(root.id(), group.id(), tagged.id()).unitTotal()).isEqualByComparingTo("100.00");

        catalogConfigurationService.updateRule(group.id(), exact.id(), new UpdateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, tagged.id(), SelectionPricingPolicy.INCLUDED, null, null, 100,
                false, exact.version()));
        assertThat(quoteOne(root.id(), group.id(), tagged.id()).unitTotal()).isEqualByComparingTo("180.00");
        MenuSelectionRuleResponse fixed = catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, tagged.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 110));
        assertThat(quoteOne(root.id(), group.id(), tagged.id()).unitTotal()).isEqualByComparingTo("115.00");
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.TAG, generic.id(), SelectionPricingPolicy.INCLUDED, null, null, fixed.priority()));
        assertError(root.id(), new MenuItemQuoteRequest(1, List.of(groupRequest(group.id(), selection(tagged.id(), 1)))),
                CatalogDomainError.MENU_CONFIGURATION_INVALID);
        assertThat(broad.active()).isTrue();
    }

    @Test
    void nestedIncludedSelectionOnlyAddsNestedModifierAdjustmentAndAllowsNonStandaloneSelection() {
        MenuItemResponse box = createItem("Caja", "Cajas", "250.00", true);
        MenuItemResponse roll = createItem("California", "Rollos", "79.00", false);
        MenuItemResponse topping = createItem("Olas Cremosas", "Toppings", "15.00", false);
        MenuSelectionGroupResponse boxGroup = catalogConfigurationService.createGroup(box.id(),
                new CreateMenuSelectionGroupRequest("Rollo", 1, 1, false, 0));
        catalogConfigurationService.createRule(boxGroup.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, roll.id(), SelectionPricingPolicy.INCLUDED, null, null, 0));
        MenuSelectionGroupResponse rollGroup = catalogConfigurationService.createGroup(roll.id(),
                new CreateMenuSelectionGroupRequest("Topping", 1, 1, false, 0));
        catalogConfigurationService.createRule(rollGroup.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, topping.id(), SelectionPricingPolicy.FIXED_SURCHARGE,
                null, new BigDecimal("15.00"), 0));
        MenuItemQuoteResponse quote = catalogConfigurationService.quote(box.id(), new MenuItemQuoteRequest(1,
                List.of(groupRequest(boxGroup.id(), new MenuQuoteSelectionRequest(roll.id(), 1,
                        List.of(groupRequest(rollGroup.id(), selection(topping.id(), 1))))))));
        assertThat(quote.unitAdjustmentTotal()).isEqualByComparingTo("15.00");
        assertThat(quote.total()).isEqualByComparingTo("265.00");
        assertError(roll.id(), new MenuItemQuoteRequest(1, List.of()), CatalogDomainError.MENU_ITEM_NOT_ORDERABLE);
    }

    @Test
    void selectionValidationRejectsIncompleteDuplicateAndUnmatchedSelections() {
        MenuItemResponse root = createItem("Caja", "Cajas", "100.00", true);
        MenuItemResponse allowed = createItem("Permitido", "Rollos", "20.00", true);
        MenuItemResponse unallowed = createItem("No permitido", "Rollos", "20.00", true);
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(root.id(),
                new CreateMenuSelectionGroupRequest("Dos", 2, 2, false, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, allowed.id(), SelectionPricingPolicy.INCLUDED, null, null, 0));
        assertError(root.id(), new MenuItemQuoteRequest(1, List.of()), CatalogDomainError.MENU_CONFIGURATION_INCOMPLETE);
        assertError(root.id(), new MenuItemQuoteRequest(1, List.of(groupRequest(group.id(), selection(allowed.id(), 2)))),
                CatalogDomainError.MENU_SELECTION_DUPLICATE_NOT_ALLOWED);
        assertError(root.id(), new MenuItemQuoteRequest(1, List.of(groupRequest(group.id(),
                selection(allowed.id(), 1), selection(unallowed.id(), 1)))), CatalogDomainError.MENU_SELECTION_NOT_ALLOWED);
    }

    @Test
    void operationalConfigurationExcludesItsParentWhilePreservingOrderedUnavailableCandidates() {
        MenuItemResponse root = createItem("Charola", "Charolas", "300.00", true);
        MenuItemResponse second = createItem("Beta", "Rollos", "90.00", true);
        MenuItemResponse first = createItem("Alfa", "Rollos", "80.00", true);
        MenuItemResponse unavailable = createItem("Camarón", "Rollos", "99.00", true);
        unavailable = menuCatalogService.update(unavailable.id(), new UpdateMenuItemRequest(unavailable.name(), unavailable.description(),
                unavailable.category(), unavailable.price(), true, false, true, unavailable.displayOrder(), unavailable.version()));
        Long unavailableId = unavailable.id();
        CatalogTagResponse roll = createTag("roll", "Rollos");
        root = assignTags(root, roll);
        assignTags(first, roll); assignTags(second, roll); assignTags(unavailable, roll);
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(root.id(),
                new CreateMenuSelectionGroupRequest("Elige", 0, 3, true, 0));
        catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.TAG, roll.id(), SelectionPricingPolicy.INCLUDED, null, null, 0));
        MenuItemConfigurationResponse configuration = catalogConfigurationService.operationalConfiguration(root.id());
        assertThat(configuration.groups().get(0).options()).extracting(MenuSelectionOptionResponse::name)
                .containsExactly("Alfa", "Beta", "Camarón");
        assertThat(configuration.groups().get(0).options()).filteredOn(option -> option.menuItemId().equals(unavailableId))
                .allSatisfy(option -> assertThat(option.available()).isFalse());
        assertThat(configuration.groups().get(0).options()).extracting(MenuSelectionOptionResponse::menuItemId)
                .doesNotContain(root.id()).contains(first.id(), second.id(), unavailable.id());
        assertThat(quoteOne(root.id(), group.id(), first.id()).total()).isEqualByComparingTo("300.00");
    }

    @Test
    void tagManagementIsVersionedAndRawDefinitionRetainsInactiveConfiguration() {
        MenuItemResponse item = createItem("Rollo", "Rollos", "79.00", true);
        CatalogTagResponse created = createTag("roll_classic", "Clásico");
        MenuItemResponse tagged = assignTags(item, created);
        assertThat(tagged.version()).isEqualTo(item.version() + 1);
        assertThat(tagged.tags()).extracting(CatalogTagSummary::code).containsExactly("ROLL_CLASSIC");
        assertThatThrownBy(() -> catalogConfigurationService.updateTag(created.id(), new UpdateCatalogTagRequest(
                "Otro", true, 0, created.version() + 1)))
                .isInstanceOf(CatalogConfigurationException.class)
                .extracting(exception -> ((CatalogConfigurationException) exception).getError())
                .isEqualTo(CatalogDomainError.CATALOG_TAG_VERSION_CONFLICT);
        MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(item.id(),
                new CreateMenuSelectionGroupRequest("Opciones", 0, 1, false, 0));
        MenuSelectionRuleResponse rule = catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.TAG, created.id(), SelectionPricingPolicy.INCLUDED, null, null, 0));
        catalogConfigurationService.archiveGroup(item.id(), group.id());
        catalogConfigurationService.archiveRule(group.id(), rule.id());
        MenuItemConfigurationDefinitionResponse definition = catalogConfigurationService.configurationDefinition(item.id());
        assertThat(definition.groups()).singleElement().satisfies(groupDefinition -> {
            assertThat(groupDefinition.group().active()).isFalse();
            assertThat(groupDefinition.rules()).singleElement().satisfies(rawRule -> assertThat(rawRule.active()).isFalse());
        });
    }

    @Test
    void cycleAndDepthAreRejectedDeterministically() {
        MenuItemResponse root = createItem("Raíz", "Prueba", "10.00", true);
        MenuSelectionGroupResponse cycleGroup = catalogConfigurationService.createGroup(root.id(),
                new CreateMenuSelectionGroupRequest("Ciclo", 1, 1, false, 0));
        catalogConfigurationService.createRule(cycleGroup.id(), new CreateMenuSelectionRuleRequest(
                SelectionRuleTargetType.ITEM, root.id(), SelectionPricingPolicy.INCLUDED, null, null, 0));
        assertError(root.id(), new MenuItemQuoteRequest(1, List.of(groupRequest(cycleGroup.id(), selection(root.id(), 1)))),
                CatalogDomainError.MENU_CONFIGURATION_CYCLE);

        List<MenuItemResponse> chain = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) {
            chain.add(createItem("Depth " + index, "Prueba", "10.00", true));
        }
        List<MenuSelectionGroupResponse> chainGroups = new java.util.ArrayList<>();
        for (int index = 0; index < 9; index++) {
            MenuSelectionGroupResponse group = catalogConfigurationService.createGroup(chain.get(index).id(),
                    new CreateMenuSelectionGroupRequest("Nivel " + index, 1, 1, false, 0));
            catalogConfigurationService.createRule(group.id(), new CreateMenuSelectionRuleRequest(
                    SelectionRuleTargetType.ITEM, chain.get(index + 1).id(), SelectionPricingPolicy.INCLUDED,
                    null, null, 0));
            chainGroups.add(group);
        }
        List<MenuQuoteGroupRequest> nestedGroups = List.of();
        for (int index = chainGroups.size() - 1; index >= 0; index--) {
            nestedGroups = List.of(new MenuQuoteGroupRequest(chainGroups.get(index).id(), List.of(
                    new MenuQuoteSelectionRequest(chain.get(index + 1).id(), 1, nestedGroups))));
        }
        assertError(chain.get(0).id(), new MenuItemQuoteRequest(1, nestedGroups),
                CatalogDomainError.MENU_CONFIGURATION_INVALID);
    }

    private MenuItemResponse createItem(String name, String category, String price, boolean standaloneOrderable) {
        return menuCatalogService.create(new CreateMenuItemRequest(name, null, category, new BigDecimal(price), true, standaloneOrderable, 0));
    }
    private CatalogTagResponse createTag(String code, String name) { return catalogConfigurationService.createTag(new CreateCatalogTagRequest(code, name, null)); }
    private MenuItemResponse assignTags(MenuItemResponse item, CatalogTagResponse... tags) {
        return catalogConfigurationService.replaceItemTags(item.id(), new ReplaceMenuItemTagsRequest(item.version(), java.util.Arrays.stream(tags).map(CatalogTagResponse::id).toList()));
    }
    private MenuItemQuoteResponse quoteOne(Long rootId, Long groupId, Long selectedItemId) {
        return catalogConfigurationService.quote(rootId, new MenuItemQuoteRequest(1, List.of(groupRequest(groupId, selection(selectedItemId, 1)))));
    }
    private MenuQuoteGroupRequest groupRequest(Long groupId, MenuQuoteSelectionRequest... selections) { return new MenuQuoteGroupRequest(groupId, List.of(selections)); }
    private MenuQuoteSelectionRequest selection(Long itemId, int quantity) { return new MenuQuoteSelectionRequest(itemId, quantity, List.of()); }
    private void assertError(Long rootId, MenuItemQuoteRequest request, CatalogDomainError expectedError) {
        assertThatThrownBy(() -> catalogConfigurationService.quote(rootId, request)).isInstanceOf(CatalogConfigurationException.class)
                .extracting(exception -> ((CatalogConfigurationException) exception).getError()).isEqualTo(expectedError);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
