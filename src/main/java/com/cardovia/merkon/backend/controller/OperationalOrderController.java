package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.orderread.OperationalOrderDetailResponse;
import com.cardovia.merkon.backend.orderread.OperationalOrderReadService;
import com.cardovia.merkon.backend.orderread.OperationalOrderSummaryResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;


import org.springframework.web.bind.annotation.RequestParam;
import com.cardovia.merkon.backend.entity.OrderSource;
import com.cardovia.merkon.backend.orderread.HistoricalOrderSummaryResponse;
import com.cardovia.merkon.backend.orderread.HistoricalOrdersPageResponse;
import com.cardovia.merkon.backend.security.TrustedBusinessContext;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/** Versioned operational read API; command endpoints remain in their existing controllers. */
@RestController
@RequestMapping("/api/v1/orders")
public class OperationalOrderController {

    private final OperationalOrderReadService operationalOrderReadService;
    private final TrustedBusinessContext trustedBusinessContext;

    public OperationalOrderController(OperationalOrderReadService operationalOrderReadService,
                                      TrustedBusinessContext trustedBusinessContext) {
        this.operationalOrderReadService = Objects.requireNonNull(operationalOrderReadService,
                "operationalOrderReadService must not be null");
        this.trustedBusinessContext = Objects.requireNonNull(trustedBusinessContext,
                "trustedBusinessContext must not be null");
    }

    @GetMapping("/active")
    public ResponseEntity<List<OperationalOrderSummaryResponse>> active(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(operationalOrderReadService.activeOrders(businessId(jwt)));
    }

    @GetMapping("/analytics")
    public ResponseEntity<com.cardovia.merkon.backend.orderread.HistoricalAnalyticsResponse> analytics(@AuthenticationPrincipal Jwt jwt,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return ResponseEntity.ok(operationalOrderReadService.historicalAnalytics(businessId(jwt), from, to));
    }

    @GetMapping
    public ResponseEntity<HistoricalOrdersPageResponse> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Page<HistoricalOrderSummaryResponse> pageResult = operationalOrderReadService.historicalOrders(businessId(jwt), from, to, source, status, page, size);
        return ResponseEntity.ok(new HistoricalOrdersPageResponse(
                pageResult.getContent(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperationalOrderDetailResponse> order(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(operationalOrderReadService.order(businessId(jwt), id));
    }

    private Long businessId(Jwt jwt) { return trustedBusinessContext.requireBusinessId(jwt); }
}
