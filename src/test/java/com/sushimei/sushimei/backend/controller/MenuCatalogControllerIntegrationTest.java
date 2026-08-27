package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItemDefaultComponent;
import com.sushimei.sushimei.backend.catalog.MenuItemDefaultComponentRepository;
import com.sushimei.sushimei.backend.catalog.MenuItemPricingMode;
import com.sushimei.sushimei.backend.catalog.UpdateMenuItemRequest;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "OWNER")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, MenuCatalogControllerIntegrationTest.TestInfrastructureConfiguration.class})
class MenuCatalogControllerIntegrationTest {

    private static final String BASE_PATH = "/api/v1/menu/items";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MenuCatalogRepository menuCatalogRepository;

    @Autowired
    private MenuItemDefaultComponentRepository componentRepository;

    @BeforeEach
    void removeCatalogFixtures() {
        jdbcTemplate.update("delete from public.promotion_targets");
        jdbcTemplate.update("delete from public.promotion_weekdays");
        jdbcTemplate.update("delete from public.promotions");
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.menu_item_default_components");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
    }

    @Test
    void createUsesDefaultsNormalizesExactPriceAndReturnsOnlyTheDtoContract() throws Exception {
        CatalogItemView created = create(new CreateMenuItemRequest(
                "  California Roll  ", "  Surimi y aguacate  ", "  Rollos  ",
                new BigDecimal("79.5"), null, null, null));

        assertThat(created.name()).isEqualTo("California Roll");
        assertThat(created.description()).isEqualTo("Surimi y aguacate");
        assertThat(created.category()).isEqualTo("Rollos");
        assertThat(created.price()).isEqualByComparingTo("79.50");
        assertThat(created.active()).isTrue();
        assertThat(created.available()).isTrue();
        assertThat(created.pricingMode()).isEqualTo(MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS);
        assertThat(created.requiresConfiguration()).isFalse();
        assertThat(created.displayOrder()).isZero();
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(created.updatedAt());
        assertThat(jdbcTemplate.queryForObject(
                "select price_amount from public.menu_items where id = ?",
                BigDecimal.class,
                created.id())).isEqualByComparingTo("79.50");
    }

    @Test
    void listDefaultsToActiveRowsOrdersDeterministicallyAndCanIncludeArchivedRows() throws Exception {
        CatalogItemView sushi = create(item("Sushi", "Nigiri", "Sushi", "45.00", true, 2));
        CatalogItemView rollB = create(item("Beta Roll", "Rollos", "Rollos", "80.00", true, 1));
        CatalogItemView rollA = create(item("Alfa Roll", "Rollos", "Rollos", "79.00", true, 1));
        CatalogItemView archived = create(item("Archivado", "Bebidas", "Bebidas", "20.00", true, 0));

        mockMvc.perform(delete(BASE_PATH + "/{id}", archived.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(rollA.id()))
                .andExpect(jsonPath("$[1].id").value(rollB.id()))
                .andExpect(jsonPath("$[2].id").value(sushi.id()))
                .andExpect(jsonPath("$[3]").doesNotExist());

        mockMvc.perform(get(BASE_PATH).param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(archived.id()))
                .andExpect(jsonPath("$[1].id").value(rollA.id()))
                .andExpect(jsonPath("$[2].id").value(rollB.id()))
                .andExpect(jsonPath("$[3].id").value(sushi.id()));
    }

    @Test
    void selectionSumPricingModeIsRepresentedWithoutRoundingTheZeroRootPrice() throws Exception {
        CatalogItemView created = create(new CreateMenuItemRequest(
                "Arma tu Charola", null, "Charolas/Sushi Box", BigDecimal.ZERO,
                true, true, 0, MenuItemPricingMode.SELECTION_SUM));

        assertThat(created.pricingMode()).isEqualTo(MenuItemPricingMode.SELECTION_SUM);
        assertThat(created.price()).isEqualByComparingTo("0.00");
    }

    @Test
    void getReturnsExistingDtoAndMissingItemsUseTheStableNotFoundError() throws Exception {
        CatalogItemView created = create(item("California", "Rollos", "Rollos", "79.00", true, 0));

        mockMvc.perform(get(BASE_PATH + "/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.price").value(79.00))
                .andExpect(jsonPath("$.priceAmount").doesNotExist())
                .andExpect(jsonPath("$.hibernateLazyInitializer").doesNotExist());

        mockMvc.perform(get(BASE_PATH + "/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MENU_ITEM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Elemento de menú no encontrado."));
    }

    @Test
    void componentsEndpointReturnsTheCompleteGenericDefaultComponentDefinitionForAnyMenuItem() throws Exception {
        CatalogItemView created = create(item("Elemento configurable", "Prueba", "Varios", "79.00", true, 0));
        componentRepository.saveAndFlush(MenuItemDefaultComponent.create(
                menuCatalogRepository.findById(created.id()).orElseThrow(),
                "CABLE_USB", "Cable USB", "2 metros", true, true, 0));
        componentRepository.saveAndFlush(MenuItemDefaultComponent.create(
                menuCatalogRepository.findById(created.id()).orElseThrow(),
                "BATERIA", "Batería", null, true, false, 1));

        mockMvc.perform(get(BASE_PATH + "/{id}/components", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CABLE_USB"))
                .andExpect(jsonPath("$[0].detail").value("2 metros"))
                .andExpect(jsonPath("$[0].includedByDefault").value(true))
                .andExpect(jsonPath("$[0].removable").value(true))
                .andExpect(jsonPath("$[1].code").value("BATERIA"))
                .andExpect(jsonPath("$[1].removable").value(false));
    }

    @Test
    void updateUsesExactMoneyAndRejectsAStaleVersion() throws Exception {
        CatalogItemView created = create(item("California", "Rollos", "Rollos", "79.00", true, 0));

        UpdateMenuItemRequest update = new UpdateMenuItemRequest(
                "California Especial", "Aguacate", "Rollos", new BigDecimal("80.50"),
                true, false, true, 3, created.version());

        CatalogItemView updated = update(created.id(), update);

        assertThat(updated.name()).isEqualTo("California Especial");
        assertThat(updated.price()).isEqualByComparingTo("80.50");
        assertThat(updated.available()).isFalse();
        assertThat(updated.displayOrder()).isEqualTo(3);
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(updated.updatedAt()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "select price_amount from public.menu_items where id = ?",
                BigDecimal.class,
                created.id())).isEqualByComparingTo("80.50");

        mockMvc.perform(put(BASE_PATH + "/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_ITEM_VERSION_CONFLICT"));
    }

    @Test
    void deleteArchivesTheRowAndUpdateCanDeliberatelyReactivateIt() throws Exception {
        CatalogItemView created = create(item("Coca Cola", "Bebidas", "Bebidas", "20.00", true, 0));

        mockMvc.perform(delete(BASE_PATH + "/{id}", created.id()))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "select active from public.menu_items where id = ?",
                Boolean.class,
                created.id())).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select available from public.menu_items where id = ?",
                Boolean.class,
                created.id())).isFalse();

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        CatalogItemView archived = fetch(created.id());
        assertThat(archived.active()).isFalse();
        assertThat(archived.available()).isFalse();

        CatalogItemView reactivated = update(created.id(), new UpdateMenuItemRequest(
                archived.name(), archived.description(), archived.category(), archived.price(),
                true, true, true, archived.displayOrder(), archived.version()));

        assertThat(reactivated.active()).isTrue();
        assertThat(reactivated.available()).isTrue();
    }

    @Test
    void invalidCatalogInputReturnsStableBadRequestWithoutRounding() throws Exception {
        List<CreateMenuItemRequest> invalidRequests = List.of(
                item(" ", "Rollos", "Rollos", "79.00", true, 0),
                item("California", "Rollos", " ", "79.00", true, 0),
                item("California", "Rollos", "Rollos", "0.00", true, 0),
                item("California", "Rollos", "Rollos", "-1.00", true, 0),
                item("California", "Rollos", "Rollos", "10.005", true, 0),
                item("California", "Rollos", "Rollos", "100000000000000000.00", true, 0),
                item("California", "Rollos", "Rollos", "79.00", true, -1)
        );

        for (CreateMenuItemRequest invalidRequest : invalidRequests) {
            mockMvc.perform(post(BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_MENU_ITEM"));
        }


        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MENU_ITEM"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isZero();
    }

    private CatalogItemView create(CreateMenuItemRequest request) throws Exception {
        String response = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return toView(objectMapper.readTree(response));
    }

    private CatalogItemView update(Long id, UpdateMenuItemRequest request) throws Exception {
        String response = mockMvc.perform(put(BASE_PATH + "/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return toView(objectMapper.readTree(response));
    }

    private CatalogItemView fetch(Long id) throws Exception {
        String response = mockMvc.perform(get(BASE_PATH + "/{id}", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return toView(objectMapper.readTree(response));
    }

    private CatalogItemView toView(JsonNode node) {
        return new CatalogItemView(
                node.required("id").asLong(),
                node.required("name").asText(),
                node.path("description").isNull() ? null : node.path("description").asText(),
                node.required("category").asText(),
                node.required("price").decimalValue(),
                MenuItemPricingMode.valueOf(node.required("pricingMode").asText()),
                node.required("active").asBoolean(),
                node.required("available").asBoolean(),
                node.required("requiresConfiguration").asBoolean(),
                node.required("displayOrder").asInt(),
                node.required("version").asLong(),
                node.required("createdAt").asText(),
                node.required("updatedAt").asText());
    }

    private record CatalogItemView(
            Long id,
            String name,
            String description,
            String category,
            BigDecimal price,
            MenuItemPricingMode pricingMode,
            boolean active,
            boolean available,
            boolean requiresConfiguration,
            int displayOrder,
            long version,
            String createdAt,
            String updatedAt) {
    }
    private CreateMenuItemRequest item(String name,
                                       String description,
                                       String category,
                                       String price,
                                       boolean available,
                                       int displayOrder) {
        return new CreateMenuItemRequest(
                name,
                description,
                category,
                new BigDecimal(price),
                available,
                true,
                displayOrder);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
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
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(String path, Object... variables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path, variables)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-owner").roles("OWNER"));
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String path, Object... variables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path, variables)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-owner").roles("OWNER"));
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder put(String path, Object... variables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path, variables)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-owner").roles("OWNER"));
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder delete(String path, Object... variables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path, variables)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-owner").roles("OWNER"));
    }
}
