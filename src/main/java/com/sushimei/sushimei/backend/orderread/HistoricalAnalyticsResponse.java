package com.sushimei.sushimei.backend.orderread;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record HistoricalAnalyticsResponse(
        Instant from,
        Instant to,
        BigDecimal completedRevenue,
        long completedOrderCount,
        BigDecimal averageCompletedTicket,
        long voidedOrderCount,
        List<SalesBySourceResponse> salesBySource
) {}
