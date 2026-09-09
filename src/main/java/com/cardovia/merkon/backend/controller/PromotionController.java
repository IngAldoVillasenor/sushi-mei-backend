package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.promotion.CreatePromotionRequest;
import com.cardovia.merkon.backend.promotion.PromotionQuoteRequest;
import com.cardovia.merkon.backend.promotion.PromotionQuoteResponse;
import com.cardovia.merkon.backend.promotion.PromotionResponse;
import com.cardovia.merkon.backend.promotion.PromotionService;
import com.cardovia.merkon.backend.promotion.TemporalPromotionQuoteService;
import com.cardovia.merkon.backend.promotion.UpdatePromotionRequest;
import com.cardovia.merkon.backend.security.TrustedBusinessContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {

    private final PromotionService promotionService;
    private final TemporalPromotionQuoteService temporalPromotionQuoteService;
    private final TrustedBusinessContext trustedBusinessContext;

    public PromotionController(PromotionService promotionService,
                               TemporalPromotionQuoteService temporalPromotionQuoteService,
                               TrustedBusinessContext trustedBusinessContext) {
        this.promotionService = Objects.requireNonNull(promotionService, "promotionService must not be null");
        this.temporalPromotionQuoteService = Objects.requireNonNull(temporalPromotionQuoteService,
                "temporalPromotionQuoteService must not be null");
        this.trustedBusinessContext = Objects.requireNonNull(trustedBusinessContext,
                "trustedBusinessContext must not be null");
    }

    @GetMapping
    public List<PromotionResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return promotionService.list(businessId(jwt), includeInactive);
    }

    @GetMapping("/active")
    public List<PromotionResponse> listActive(@AuthenticationPrincipal Jwt jwt) {
        return temporalPromotionQuoteService.listApplicable(businessId(jwt));
    }

    @GetMapping("/{id}")
    public PromotionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return promotionService.get(businessId(jwt), id);
    }

    @PostMapping
    public ResponseEntity<PromotionResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePromotionRequest request) {
        PromotionResponse created = promotionService.create(businessId(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public PromotionResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody UpdatePromotionRequest request) {
        return promotionService.update(businessId(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        promotionService.archive(businessId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/quote")
    public PromotionQuoteResponse quote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PromotionQuoteRequest request) {
        return temporalPromotionQuoteService.quote(businessId(jwt), request);
    }

    private Long businessId(Jwt jwt) {
        return trustedBusinessContext.requireBusinessId(jwt);
    }
}
