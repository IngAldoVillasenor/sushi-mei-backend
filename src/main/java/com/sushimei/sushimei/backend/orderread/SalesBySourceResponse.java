package com.sushimei.sushimei.backend.orderread;

import java.math.BigDecimal;
import com.sushimei.sushimei.backend.entity.OrderSource;

public record SalesBySourceResponse(
        OrderSource source,
        long completedOrderCount,
        BigDecimal completedRevenue
) {}
