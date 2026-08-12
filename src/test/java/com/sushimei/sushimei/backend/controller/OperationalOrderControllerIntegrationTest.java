package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        OperationalOrderControllerIntegrationTest.TestInfrastructureConfiguration.class})
class OperationalOrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void versionedOperationalReadsRequireAuthenticationAndAllowAllOperationalRoles() throws Exception {
        OrderRecord order = order("PENDING", 1);

        mockMvc.perform(get("/api/v1/orders/active"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders/active").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/active").with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/active").with(user("cashier").roles("CASHIER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/active").with(user("kitchen").roles("KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(user("cashier").roles("CASHIER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(user("kitchen").roles("KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders").with(user("kitchen").roles("KITCHEN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailAndMissingOrderUseStableDtoAndErrorContracts() throws Exception {
        OrderRecord order = order("PENDING", 1);

        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()).with(user("cashier").roles("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.getId()))
                .andExpect(jsonPath("$.createdAt").value("2026-08-11T12:01:00Z"))
                .andExpect(jsonPath("$.legacyOrderDetails").value("Detalle operativo"))
                .andExpect(jsonPath("$.requestFingerprint").doesNotExist())
                .andExpect(jsonPath("$.orderLines").doesNotExist());
        mockMvc.perform(get("/api/v1/orders/active").with(user("cashier").roles("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdAt").value("2026-08-11T12:01:00Z"));
        mockMvc.perform(get("/api/v1/orders/{id}", 999999L).with(user("manager").roles("MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private OrderRecord order(String status, int minute) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("521477200" + minute);
        order.setPaymentMethod(OrderPaymentMethod.CASH);
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 12, minute));
        order.setOrderDetails("Detalle operativo");
        return orderRepository.saveAndFlush(order);
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
