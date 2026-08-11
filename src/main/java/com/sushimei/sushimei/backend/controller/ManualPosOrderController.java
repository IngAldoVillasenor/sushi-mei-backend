package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.pos.ManualPosOrderRequest;
import com.sushimei.sushimei.backend.pos.ManualPosOrderResponse;
import com.sushimei.sushimei.backend.pos.ManualPosOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class ManualPosOrderController {
    private final ManualPosOrderService manualPosOrderService;

    public ManualPosOrderController(ManualPosOrderService manualPosOrderService) {
        this.manualPosOrderService = manualPosOrderService;
    }

    @PostMapping
    public ResponseEntity<ManualPosOrderResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                          @Valid @RequestBody ManualPosOrderRequest request) {
        ManualPosOrderResponse response = manualPosOrderService.create(Long.valueOf(jwt.getSubject()), request);
        if (response.result() == com.sushimei.sushimei.backend.pos.ManualOrderResult.ALREADY_CREATED) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
