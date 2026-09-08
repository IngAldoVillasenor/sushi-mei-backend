package com.cardovia.merkon.backend.orderread;

import java.math.BigDecimal;
import com.cardovia.merkon.backend.entity.OrderSource;

public record SalesBySourceResponse(
        OrderSource source,
        long completedOrderCount,
        BigDecimal completedRevenue
) {}
