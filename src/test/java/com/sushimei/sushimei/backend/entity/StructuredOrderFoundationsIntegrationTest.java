package com.sushimei.sushimei.backend.entity;

import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, StructuredOrderFoundationsIntegrationTest.TestInfrastructureConfiguration.class})
@Transactional
class StructuredOrderFoundationsIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void removeStructuredOrderFixtures() {
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void persistsAndReloadsStructuredLinesAndTypedMetadataWithoutChangingLegacyFields() {
        OrderRecord order = validOrder(101L);
        order.setOrderSource(OrderSource.WHATSAPP_AI);
        order.setFulfillmentType(OrderFulfillmentType.DELIVERY);
        order.setPaymentMethod(OrderPaymentMethod.CASH);
        order.setCashDenomination(new BigDecimal("100.00"));
        order.setTransferReceiptPath("r".repeat(1024));
        order.addOrderLine(OrderLineRecord.create(1002L, 2, "Coca Cola", 1,
                new BigDecimal("20.00"), new BigDecimal("20.00")));
        order.addOrderLine(OrderLineRecord.create(1001L, 1, "California Roll", 2,
                new BigDecimal("79.00"), new BigDecimal("158.00")));

        orderRepository.saveAndFlush(order);
        entityManager.clear();

        OrderRecord reloaded = orderRepository.findBySourceCartId(101L).orElseThrow();

        assertThat(reloaded.getTotalAmount()).isEqualTo(178.00d);
        assertThat(reloaded.getTotalAmountAmount()).isEqualByComparingTo("178.00");
        assertThat(reloaded.getOrderSource()).isEqualTo(OrderSource.WHATSAPP_AI);
        assertThat(reloaded.getFulfillmentType()).isEqualTo(OrderFulfillmentType.DELIVERY);
        assertThat(reloaded.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CASH);
        assertThat(reloaded.getCashDenomination()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getTransferReceiptPath()).hasSize(1024);
        assertThat(reloaded.getOrderLines()).extracting(OrderLineRecord::getLinePosition)
                .containsExactly(1, 2);
        assertThat(reloaded.getOrderLines()).extracting(OrderLineRecord::getDishName)
                .containsExactly("California Roll", "Coca Cola");
        assertThat(reloaded.getOrderLines()).extracting(OrderLineRecord::getLineTotalAmount)
                .containsExactly(new BigDecimal("158.00"), new BigDecimal("20.00"));
    }

    @Test
    void sourceCartUniquenessAllowsMultipleHistoricalNullsButRejectsDuplicateNonNullValues() {
        orderRepository.saveAndFlush(validOrder(null));
        orderRepository.saveAndFlush(validOrder(null));
        orderRepository.saveAndFlush(validOrder(202L));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(validOrder(202L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnsupportedTypedMetadataAndNonPositiveCashDenomination() {
        Long orderId = insertLegacyCompatibleOrder(null);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        update public.orders set order_source = 'WEB' where id = ?
                        """, orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        update public.orders set fulfillment_type = 'SHIPMENT' where id = ?
                        """, orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        update public.orders set payment_method = 'CRYPTO' where id = ?
                        """, orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        update public.orders set cash_denomination = 0.00 where id = ?
                        """, orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesStructuredLineConstraints() {
        Long orderId = insertLegacyCompatibleOrder(303L);

        assertThatThrownBy(() -> insertLine(orderId, 1L, 1, 0, "10.00", "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLine(orderId, 2L, 1, 1, "0.00", "1.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLine(orderId, 3L, 1, 1, "1.00", "0.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLine(orderId, 4L, 1, 1, "1.00", "2.00"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertLine(orderId, 10L, 1, 1, "10.00", "10.00");
        assertThatThrownBy(() -> insertLine(orderId, 11L, 1, 1, "10.00", "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLine(orderId, 10L, 2, 1, "10.00", "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderLineFactoryRejectsInvalidInvariantInputsBeforePersistence() {
        assertThatThrownBy(() -> OrderLineRecord.create(1L, 1, "Maki", 1,
                new BigDecimal("10.00"), new BigDecimal("10.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderLineRecord.create(1L, 1, "Maki", 1,
                new BigDecimal("10.001"), new BigDecimal("10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OrderRecord validOrder(Long sourceCartId) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("5214770000001");
        order.setStatus("PENDING");
        order.setCreatedAt(LocalDateTime.of(2026, 8, 7, 12, 0));
        order.setOrderDetails("Legacy-compatible summary");
        order.setTotalAmount(178.00d);
        order.setTotalAmountAmount(new BigDecimal("178.00"));
        order.setSourceCartId(sourceCartId);
        return order;
    }

    private Long insertLegacyCompatibleOrder(Long sourceCartId) {
        jdbcTemplate.update("""
                        insert into public.orders (
                            phone_number, total_amount, total_amount_amount, status, created_at, source_cart_id
                        ) values (?, ?, ?, ?, current_timestamp, ?)
                        """,
                "5214770000001", 10.50d, new BigDecimal("10.50"), "PENDING", sourceCartId);
        return jdbcTemplate.queryForObject("select max(id) from public.orders", Long.class);
    }

    private void insertLine(Long orderId,
                            Long sourceCartItemId,
                            int linePosition,
                            int quantity,
                            String unitPriceAmount,
                            String lineTotalAmount) {
        jdbcTemplate.update("""
                        insert into public.order_lines (
                            order_id, source_cart_item_id, line_position, dish_name, quantity,
                            unit_price_amount, line_total_amount
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """,
                orderId, sourceCartItemId, linePosition, "Maki", quantity,
                new BigDecimal(unitPriceAmount), new BigDecimal(lineTotalAmount));
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
