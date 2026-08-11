package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.catalog.CatalogConfigurationService;
import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
import com.sushimei.sushimei.backend.catalog.MenuItemResponse;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.test.context.support.WithMockUser;
import static org.mockito.Mockito.mock;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "OWNER")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, MenuConfigurationControllerIntegrationTest.TestInfrastructureConfiguration.class})
class MenuConfigurationControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MenuCatalogService menuCatalogService;
    @Autowired private CatalogConfigurationService catalogConfigurationService;

    @BeforeEach
    void cleanCatalogTables() {
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
    }

    @Test
    void tagCrudUsesNormalizedImmutableCodeSoftArchiveAndVersionConflicts() throws Exception {
        String created = mockMvc.perform(post("/api/v1/menu/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"roll_classic\",\"name\":\"Clásicos\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ROLL_CLASSIC"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        long tagId = objectMapper.readTree(created).required("id").asLong();
        long version = objectMapper.readTree(created).required("version").asLong();

        mockMvc.perform(get("/api/v1/menu/tags"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("ROLL_CLASSIC"));
        mockMvc.perform(put("/api/v1/menu/tags/{id}", tagId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Clásicos\",\"active\":true,\"displayOrder\":0,\"version\":" + (version + 1) + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATALOG_TAG_VERSION_CONFLICT"));
        mockMvc.perform(delete("/api/v1/menu/tags/{id}", tagId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/menu/tags")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/menu/tags").param("includeInactive", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void managementAndOperationalEndpointsReturnDtosInsteadOfEntitiesOrPricingAlgorithms() throws Exception {
        MenuItemResponse root = menuCatalogService.create(new CreateMenuItemRequest(
                "Caja", null, "Cajas", new BigDecimal("250.00"), true, true, 0));
        MenuItemResponse roll = menuCatalogService.create(new CreateMenuItemRequest(
                "California", null, "Rollos", new BigDecimal("79.00"), true, false, 0));
        String tag = mockMvc.perform(post("/api/v1/menu/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ROLL\",\"name\":\"Rollos\",\"displayOrder\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long tagId = objectMapper.readTree(tag).required("id").asLong();
        long rootVersion = root.version();
        mockMvc.perform(put("/api/v1/menu/items/{id}/tags", roll.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemVersion\":" + roll.version() + ",\"tagIds\":[" + tagId + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0].code").value("ROLL"));
        mockMvc.perform(put("/api/v1/menu/items/{id}/tags", root.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemVersion\":" + rootVersion + ",\"tagIds\":[" + tagId + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0].code").value("ROLL"));
        String group = mockMvc.perform(post("/api/v1/menu/items/{id}/selection-groups", root.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Elige\",\"minSelections\":1,\"maxSelections\":1,\"allowDuplicates\":false}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long groupId = objectMapper.readTree(group).required("id").asLong();
        mockMvc.perform(post("/api/v1/menu/selection-groups/{id}/rules", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"TAG\",\"targetId\":" + tagId + ",\"pricingPolicy\":\"INCLUDED\",\"priority\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pricingPolicy").value("INCLUDED"));

        mockMvc.perform(get("/api/v1/menu/items/{id}/configuration", root.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].options[0].menuItemId").value(roll.id()))
                .andExpect(jsonPath("$.groups[0].options[1]").doesNotExist())
                .andExpect(jsonPath("$.groups[0].options[0].priceAdjustment").value(0.00))
                .andExpect(jsonPath("$.groups[0].options[0].pricingPolicy").doesNotExist());
        mockMvc.perform(get("/api/v1/menu/items/{id}/configuration-definition", root.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].rules[0].pricingPolicy").value("INCLUDED"))
                .andExpect(jsonPath("$.hibernateLazyInitializer").doesNotExist());
        mockMvc.perform(post("/api/v1/menu/items/{id}/quote", root.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"groups\":[{\"groupId\":" + groupId + ",\"selections\":[{\"menuItemId\":" + roll.id() + ",\"quantity\":1,\"groups\":[]}]}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(250.00))
                .andExpect(jsonPath("$.groups[0].selections[0].priceAdjustment").value(0.00));
        mockMvc.perform(get("/api/v1/menu/items").param("standaloneOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(root.id()))
                .andExpect(jsonPath("$[1]").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(rootVersion).isZero();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
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
