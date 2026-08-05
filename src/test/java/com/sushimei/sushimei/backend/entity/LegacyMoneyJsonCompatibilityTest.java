package com.sushimei.sushimei.backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMoneyJsonCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void cartItemParallelNumericAmountIsNotExposedInLegacyJson() throws Exception {
        CartItem item = new CartItem();
        item.setUnitPrice(10.5d);
        item.setUnitPriceAmount(new BigDecimal("10.50"));

        String json = objectMapper.writeValueAsString(item);

        assertThat(json).contains("unitPrice");
        assertThat(json).doesNotContain("unitPriceAmount");
    }

    @Test
    void orderParallelNumericAmountIsNotExposedInLegacyJson() throws Exception {
        OrderRecord order = new OrderRecord();
        order.setTotalAmount(10.5d);
        order.setTotalAmountAmount(new BigDecimal("10.50"));

        String json = objectMapper.writeValueAsString(order);

        assertThat(json).contains("totalAmount");
        assertThat(json).doesNotContain("totalAmountAmount");
    }
}
