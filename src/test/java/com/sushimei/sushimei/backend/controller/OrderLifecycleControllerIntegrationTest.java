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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        OrderLifecycleControllerIntegrationTest.TestInfrastructureConfiguration.class})
class OrderLifecycleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void missingAndInvalidLifecycleCommandsReturnStableErrors() throws Exception {
        OrderRecord pending = order("PENDING", OrderPaymentMethod.CASH, 1);

        mockMvc.perform(put("/api/orders/{id}/prepare", 999999L).with(user("manager").roles("MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(put("/api/orders/{id}/complete", pending.getId()).with(user("manager").roles("MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));
    }

    @Test
    void activeEndpointReturnsOperationalDtosOldestFirstWithoutEntityInternals() throws Exception {
        OrderRecord older = order("PENDING", OrderPaymentMethod.CASH, 1);
        order("COMPLETED", OrderPaymentMethod.CASH, 2);
        OrderRecord newer = order("PREPARING", OrderPaymentMethod.CASH, 3);

        mockMvc.perform(get("/api/orders/active").with(user("kitchen").roles("KITCHEN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(older.getId()))
                .andExpect(jsonPath("$[1].id").value(newer.getId()))
                .andExpect(jsonPath("$[0].requestFingerprint").doesNotExist())
                .andExpect(jsonPath("$[0].createdByUserId").doesNotExist())
                .andExpect(jsonPath("$[0].orderLines").doesNotExist());
    }

    @Test
    void roleAuthorizationRemainsUnchangedForOperationalCommands() throws Exception {
        OrderRecord cashierForbidden = order("PENDING", OrderPaymentMethod.CASH, 1);
        OrderRecord ownerPending = order("PENDING", OrderPaymentMethod.CASH, 2);
        OrderRecord managerPending = order("PENDING", OrderPaymentMethod.CASH, 3);
        OrderRecord kitchenPending = order("PENDING", OrderPaymentMethod.CASH, 4);
        OrderRecord kitchenForbiddenTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 5);
        OrderRecord ownerTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 6);
        OrderRecord managerTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 7);
        OrderRecord cashierTransfer = order("PENDING_VALIDATION", OrderPaymentMethod.TRANSFER, 8);

        mockMvc.perform(put("/api/orders/{id}/prepare", cashierForbidden.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/orders/{id}/prepare", cashierForbidden.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("cashier")
                                .roles("CASHIER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/{id}/prepare", ownerPending.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner")
                                .roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/prepare", managerPending.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("manager")
                                .roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/prepare", kitchenPending.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("kitchen")
                                .roles("KITCHEN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", kitchenForbiddenTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("kitchen")
                                .roles("KITCHEN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", ownerTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner")
                                .roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", managerTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("manager")
                                .roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/{id}/validate-payment", cashierTransfer.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("cashier")
                                .roles("CASHIER")))
                .andExpect(status().isOk());
    }

    private OrderRecord order(String status, OrderPaymentMethod paymentMethod, int minute) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("521477100" + minute);
        order.setPaymentMethod(paymentMethod);
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 11, minute));
        order.setOrderDetails("Orden de prueba");
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
