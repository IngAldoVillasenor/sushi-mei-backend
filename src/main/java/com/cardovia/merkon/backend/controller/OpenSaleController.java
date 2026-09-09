package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.pos.OpenSaleRequest;
import com.cardovia.merkon.backend.pos.OpenSaleResponse;
import com.cardovia.merkon.backend.pos.OpenSaleResult;
import com.cardovia.merkon.backend.pos.OpenSaleService;
import com.cardovia.merkon.backend.security.TrustedBusinessContext;
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
    private final TrustedBusinessContext trustedBusinessContext;

    public OpenSaleController(OpenSaleService openSaleService, TrustedBusinessContext trustedBusinessContext) {
        this.openSaleService = openSaleService;
        this.trustedBusinessContext = trustedBusinessContext;
    }

    @PostMapping
    public ResponseEntity<OpenSaleResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody OpenSaleRequest request) {
        OpenSaleResponse response = openSaleService.create(trustedBusinessContext.requireBusinessId(jwt),
                Long.valueOf(jwt.getSubject()), request);
        return response.result() == OpenSaleResult.ALREADY_CREATED
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
