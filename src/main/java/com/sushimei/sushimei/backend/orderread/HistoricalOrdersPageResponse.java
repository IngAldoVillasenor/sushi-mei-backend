package com.sushimei.sushimei.backend.orderread;

import java.util.List;

public record HistoricalOrdersPageResponse(
        List<HistoricalOrderSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
