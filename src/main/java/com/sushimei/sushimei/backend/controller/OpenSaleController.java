package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.pos.OpenSaleRequest;
import com.sushimei.sushimei.backend.pos.OpenSaleResponse;
import com.sushimei.sushimei.backend.pos.OpenSaleResult;
import com.sushimei.sushimei.backend.pos.OpenSaleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit price-bearing endpoint for a physical non-catalog sale only. */
@RestController
@RequestMapping("/api/v1/open-sales")
public class OpenSaleController {
    private final OpenSaleService openSaleService;

    public OpenSaleController(OpenSaleService openSaleService) {
        this.openSaleService = openSaleService;
    }

    @PostMapping
    public ResponseEntity<OpenSaleResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody OpenSaleRequest request) {
        OpenSaleResponse response = openSaleService.create(Long.valueOf(jwt.getSubject()), request);
        return response.result() == OpenSaleResult.ALREADY_CREATED
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
