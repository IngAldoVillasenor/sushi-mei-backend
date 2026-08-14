package com.sushimei.sushimei.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
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

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "OWNER")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, PromotionControllerIntegrationTest.TestInfrastructureConfiguration.class})
class PromotionControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MenuCatalogService menuCatalogService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.promotion_targets");
        jdbcTemplate.update("delete from public.promotion_weekdays");
        jdbcTemplate.update("delete from public.promotions");
        jdbcTemplate.update("delete from public.menu_selection_rules");
        jdbcTemplate.update("delete from public.menu_selection_groups");
        jdbcTemplate.update("delete from public.menu_item_tags");
        jdbcTemplate.update("delete from public.catalog_tags");
        jdbcTemplate.update("delete from public.menu_items");
    }

    @Test
    void promotionAggregateCrudUsesDtosVersioningAndSoftArchive() throws Exception {
        String created = mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lunes","priority":10,"benefitType":"FIXED_UNIT_PRICE",
                                 "fixedUnitPrice":69.00,"daysOfWeek":[1],
                                 "targets":[{"targetType":"ITEM","targetId":1}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROMOTION"))
                .andReturn().getResponse().getContentAsString();

        jdbcTemplate.update("""
                insert into public.menu_items (name, category, price_amount, pricing_mode, active, available, standalone_orderable,
                    display_order, created_at, updated_at, version)
                values ('California', 'Rollos', 79.00, 'BASE_PLUS_ADJUSTMENTS', true, true, true, 0, current_timestamp, current_timestamp, 0)
                """);
        long itemId = jdbcTemplate.queryForObject("select id from public.menu_items where name = 'California'", Long.class);
        created = mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lunes","priority":10,"benefitType":"FIXED_UNIT_PRICE",
                                 "fixedUnitPrice":69.00,"daysOfWeek":[1],
                                 "targets":[{"targetType":"ITEM","targetId":%d}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.benefitType").value("FIXED_UNIT_PRICE"))
                .andExpect(jsonPath("$.targets[0].targetType").value("ITEM"))
                .andReturn().getResponse().getContentAsString();
        long promotionId = objectMapper.readTree(created).required("id").asLong();
        long version = objectMapper.readTree(created).required("version").asLong();

        mockMvc.perform(get("/api/v1/promotions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(promotionId));
        mockMvc.perform(put("/api/v1/promotions/{id}", promotionId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lunes","active":true,"priority":10,"benefitType":"FIXED_UNIT_PRICE",
                                 "fixedUnitPrice":69.00,"daysOfWeek":[1],
                                 "targets":[{"targetType":"ITEM","targetId":%d}],"version":%d}
                                """.formatted(itemId, version + 1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMOTION_VERSION_CONFLICT"));
        mockMvc.perform(delete("/api/v1/promotions/{id}", promotionId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/promotions")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/promotions").param("includeInactive", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void activeEndpointReturnsOnlyActivePromotionDefinitions() throws Exception {
        jdbcTemplate.update("""
                insert into public.promotions (name, active, priority, benefit_type, fixed_unit_price_amount,
                    buy_quantity, reward_quantity, repeat_enabled, valid_from, valid_until, created_at, updated_at, version)
                values ('Activa', true, 10, 'FIXED_UNIT_PRICE', 69.00, null, null, null, null, null,
                    current_timestamp, current_timestamp, 0)
                """);
        jdbcTemplate.update("""
                insert into public.promotions (name, active, priority, benefit_type, fixed_unit_price_amount,
                    buy_quantity, reward_quantity, repeat_enabled, valid_from, valid_until, created_at, updated_at, version)
                values ('Archivada', false, 20, 'FIXED_UNIT_PRICE', 69.00, null, null, null, null, null,
                    current_timestamp, current_timestamp, 0)
                """);

        mockMvc.perform(get("/api/v1/promotions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Activa"));
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
