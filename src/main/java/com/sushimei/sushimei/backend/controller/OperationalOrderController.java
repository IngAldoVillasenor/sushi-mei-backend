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

    @GetMapping("/{id}")
    public ResponseEntity<OperationalOrderDetailResponse> order(@PathVariable Long id) {
        return ResponseEntity.ok(operationalOrderReadService.order(id));
    }
}
