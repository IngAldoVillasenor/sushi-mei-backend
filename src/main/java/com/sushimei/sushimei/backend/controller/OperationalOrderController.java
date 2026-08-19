package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.orderread.OperationalOrderDetailResponse;
import com.sushimei.sushimei.backend.orderread.OperationalOrderReadService;
import com.sushimei.sushimei.backend.orderread.OperationalOrderSummaryResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;


import org.springframework.web.bind.annotation.RequestParam;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.orderread.HistoricalOrderSummaryResponse;
import com.sushimei.sushimei.backend.orderread.HistoricalOrdersPageResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** Versioned operational read API; command endpoints remain in their existing controllers. */
@RestController
@RequestMapping("/api/v1/orders")
public class OperationalOrderController {

    private final OperationalOrderReadService operationalOrderReadService;

    public OperationalOrderController(OperationalOrderReadService operationalOrderReadService) {
        this.operationalOrderReadService = Objects.requireNonNull(operationalOrderReadService,
                "operationalOrderReadService must not be null");
    }

    @GetMapping("/active")
    public ResponseEntity<List<OperationalOrderSummaryResponse>> active() {
        return ResponseEntity.ok(operationalOrderReadService.activeOrders());
    }

    @GetMapping
    public ResponseEntity<HistoricalOrdersPageResponse> history(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Page<HistoricalOrderSummaryResponse> pageResult = operationalOrderReadService.historicalOrders(from, to, source, status, page, size);
        return ResponseEntity.ok(new HistoricalOrdersPageResponse(
                pageResult.getContent(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperationalOrderDetailResponse> order(@PathVariable Long id) {
        return ResponseEntity.ok(operationalOrderReadService.order(id));
    }
}
