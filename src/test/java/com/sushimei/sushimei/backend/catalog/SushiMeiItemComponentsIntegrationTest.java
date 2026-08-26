package com.sushimei.sushimei.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        SushiMeiItemComponentsIntegrationTest.TestInfrastructureConfiguration.class})
class SushiMeiItemComponentsIntegrationTest {

    private static final Set<Long> REVIEWED_ROLL_IDS = Set.of(
            13L, 14L, 17L, 18L, 23L, 24L, 35L, 36L, 47L, 48L, 49L,
            52L, 66L, 68L, 79L, 80L, 82L, 83L, 85L, 105L, 106L, 107L);

    @Autowired
    private MenuItemDefaultComponentRepository componentRepository;

    @Autowired
    private MenuItemComponentService componentService;

    @Autowired
    private SushiMeiItemComponentsService bootstrapService;

    @Autowired
    private SushiMeiOptionalSelectionGroupsService optionalSelectionGroupsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MenuSelectionGroupRepository selectionGroupRepository;

    @Autowired
    private MenuSelectionRuleRepository selectionRuleRepository;

    @Autowired
    private MenuCatalogService menuCatalogService;

    @Autowired
    private CatalogConfigurationService catalogConfigurationService;

    @Test
    void synchronizesTheReviewedTwentyTwoRollsAndTheirGenericDefaultComponents() {
        Map<Long, List<MenuItemDefaultComponent>> componentsByRoll = REVIEWED_ROLL_IDS.stream()
                .collect(java.util.stream.Collectors.toMap(
                        id -> id,
                        id -> componentRepository.findByMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(id)));

        assertThat(componentsByRoll).hasSize(22);
        componentsByRoll.forEach((id, components) -> {
            assertThat(components).isNotEmpty();
            assertThat(components.stream().filter(component -> component.getComponentCode().equals("ARROZ")))
                    .singleElement()
                    .satisfies(component -> {
                        assertThat(component.isIncludedByDefault()).isTrue();
                        assertThat(component.isRemovable()).isFalse();
                    });
            assertThat(components.stream().filter(component -> component.getComponentCode().equals("ALGA")))
                    .singleElement()
                    .satisfies(component -> assertThat(component.isRemovable()).isTrue());
        });

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.menu_items
                where id in (13, 14, 17, 18, 23, 24, 35, 36, 47, 48, 49, 52, 66, 68, 79, 80, 82, 83, 85, 105, 106, 107)
                    and description is not null
                """, Integer.class)).isEqualTo(22);
    }

    @Test
    void californiaAndSpecialOutsideComponentsPreserveTheirReviewedOrderAndDetail() {
        List<MenuItemDefaultComponent> california = components(24L);
        assertThat(california).extracting(MenuItemDefaultComponent::getComponentCode)
                .containsExactly("ARROZ", "ALGA", "PEPINO", "QUESO_CREMA", "SURIMI", "AJONJOLI");

        List<MenuItemDefaultComponent> bananaEbi = components(17L);
        assertThat(component(bananaEbi, "PLATANO_FRITO").getDetail()).isEqualTo("Por fuera");
        assertThat(component(bananaEbi, "AJONJOLI").getDetail()).isEqualTo("Por fuera");

        for (long quesoRollId : List.of(82L, 83L)) {
            List<MenuItemDefaultComponent> quesoRoll = components(quesoRollId);
            assertThat(quesoRoll.stream().filter(value -> value.getComponentCode().equals("ALGA")))
                    .singleElement()
                    .satisfies(component -> assertThat(component.getDetail()).isEqualTo("Por fuera"));
            assertThat(quesoRoll).extracting(MenuItemDefaultComponent::getComponentCode).doesNotContain("FRITO");
        }
    }

    @Test
    void ramenUsesTheSameGenericComponentModelAndOnlyReviewedDefaultsAreOmittable() {
        List<MenuItemDefaultComponent> ramen = components(84L);
        assertThat(ramen).extracting(MenuItemDefaultComponent::getComponentCode)
                .containsExactly("FIDEO", "ELOTE", "HUEVO", "CEBOLLIN", "CHAMPINON", "CALDO_DE_PUERCO",
                        "CARNE_CHASHU", "ALGA", "NARUTO", "GERMEN");
        assertThat(ramen).allSatisfy(component -> assertThat(component.isRemovable()).isTrue());
        assertThat(jdbcTemplate.queryForObject("select description from public.menu_items where id = 84", String.class))
                .isEqualTo("Fideo - Elote - Huevo - Cebollín - Champiñón - Caldo de Puerco - Carne Chashu - Alga - Naruto - Germen.\n"
                        + "EXTRAS: Calsa Macha $24,99\nCarne Chashu (50G) $39,99");

        assertThat(componentService.resolveActiveOmittedComponents(84L, List.of(component(ramen, "ALGA").getId())))
                .singleElement()
                .extracting(MenuItemDefaultComponent::getComponentCode)
                .isEqualTo("ALGA");
        assertThatThrownBy(() -> componentService.resolveActiveOmittedComponents(24L,
                List.of(component(components(24L), "ARROZ").getId())))
                .isInstanceOf(CatalogConfigurationException.class);
    }

    @Test
    void bootstrapMarkerPreventsLegitimateLaterCatalogEditsFromBeingRewritten() {
        jdbcTemplate.update("update public.menu_items set description = ? where id = 24", "Descripción administrada");

        bootstrapService.synchronize();

        assertThat(jdbcTemplate.queryForObject("select description from public.menu_items where id = 24", String.class))
                .isEqualTo("Descripción administrada");
        jdbcTemplate.update("update public.menu_items set description = ? where id = 24",
                "Pepino-Queso crema-Surimi-Ajonjolí");
    }

    @Test
    void yakimeshiRemainsUnseededUntilItsPhysicalRecipeIsReviewed() {
        for (long yakimeshiId : List.of(55L, 114L, 115L, 116L, 117L, 118L, 119L, 120L, 121L)) {
            assertThat(components(yakimeshiId)).isEmpty();
        }
    }

    @Test
    void reviewedOptionalSelectionDataUsesTheGenericGroupAndTagRuleModel() {
        for (Long itemId : REVIEWED_ROLL_IDS) {
            List<MenuSelectionGroup> groups = selectionGroupRepository
                    .findByParentMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(itemId).stream()
                    .filter(group -> group.getName().equals("Toppings")).toList();
            assertThat(groups).singleElement().satisfies(group -> {
                assertThat(group.getMinSelections()).isZero();
                assertThat(group.getMaxSelections()).isEqualTo(1);
                assertThat(group.isAllowDuplicates()).isFalse();
                assertThat(selectionRuleRepository.findBySelectionGroupIdAndActiveTrueOrderByPriorityDescIdAsc(group.getId()))
                        .singleElement().satisfies(rule -> {
                            assertThat(jdbcTemplate.queryForObject("""
                                    select code from public.catalog_tags
                                    where id = (select target_tag_id from public.menu_selection_rules where id = ?)
                                    """, String.class, rule.getId())).isEqualTo("TOPPING");
                            assertThat(rule.getPricingPolicy()).isEqualTo(SelectionPricingPolicy.FULL_ITEM_PRICE);
                        });
            });
            assertThat(menuCatalogService.get(itemId).requiresConfiguration()).isFalse();
        }
    }

    @Test
    void optionalToppingsAreServerResolvedThroughTheGenericSelectionQuote() {
        MenuItemConfigurationResponse configuration = catalogConfigurationService.operationalConfiguration(24L);
        MenuSelectionGroupConfigurationResponse toppings = configuration.groups().stream()
                .filter(group -> group.name().equals("Toppings")).findFirst().orElseThrow();
        assertThat(toppings.options()).extracting(MenuSelectionOptionResponse::menuItemId)
                .containsExactlyInAnyOrder(53L, 74L, 108L);
        assertThat(catalogConfigurationService.quote(24L, new MenuItemQuoteRequest(1,
                List.of(new MenuQuoteGroupRequest(toppings.id(),
                        List.of(new MenuQuoteSelectionRequest(53L, 1, List.of())))))).unitTotal())
                .isEqualByComparingTo(new BigDecimal("118.00"));
        assertThatThrownBy(() -> catalogConfigurationService.quote(24L, new MenuItemQuoteRequest(1,
                List.of(new MenuQuoteGroupRequest(toppings.id(), List.of(
                        new MenuQuoteSelectionRequest(53L, 1, List.of()),
                        new MenuQuoteSelectionRequest(74L, 1, List.of())))))))
                .isInstanceOf(CatalogConfigurationException.class);
    }

    @Test
    void optionalSelectionBootstrapReusesOneEquivalentGroupBeforeItsMarkerIsApplied() {
        Long originalGroupId = jdbcTemplate.queryForObject("""
                select id from public.menu_selection_groups
                where parent_menu_item_id = 24 and name = 'Toppings'
                """, Long.class);
        jdbcTemplate.update("delete from public.menu_selection_rules where selection_group_id = ?", originalGroupId);
        jdbcTemplate.update("""
                update public.menu_selection_groups
                set min_selections = 1, max_selections = 2, allow_duplicates = true, display_order = 7, active = false
                where id = ?
                """, originalGroupId);
        jdbcTemplate.update("update public.catalog_bootstrap_rule_sets set applied_at = null where rule_set_id = ?",
                SushiMeiOptionalSelectionGroupsService.RULE_SET_ID);

        optionalSelectionGroupsService.synchronize();

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.menu_selection_groups
                where parent_menu_item_id = 24 and name = 'Toppings'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select id from public.menu_selection_groups
                where parent_menu_item_id = 24 and name = 'Toppings'
                """, Long.class)).isEqualTo(originalGroupId);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.menu_selection_groups
                where id = ? and min_selections = 0 and max_selections = 1
                    and allow_duplicates = false and display_order = 100 and active = true
                """, Integer.class, originalGroupId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.menu_selection_rules r
                join public.catalog_tags t on t.id = r.target_tag_id
                where r.selection_group_id = ? and t.code = 'TOPPING'
                    and r.pricing_policy = 'FULL_ITEM_PRICE' and r.active = true
                """, Integer.class, originalGroupId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.catalog_bootstrap_rule_sets
                where rule_set_id = ? and applied_at is not null
                """, Integer.class, SushiMeiOptionalSelectionGroupsService.RULE_SET_ID)).isEqualTo(1);
    }

    private List<MenuItemDefaultComponent> components(Long menuItemId) {
        return componentRepository.findByMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(menuItemId);
    }

    private MenuItemDefaultComponent component(List<MenuItemDefaultComponent> components, String code) {
        return components.stream().filter(component -> component.getComponentCode().equals(code)).findFirst().orElseThrow();
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
