package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.promotion.CreatePromotionRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteResponse;
import com.sushimei.sushimei.backend.promotion.PromotionResponse;
import com.sushimei.sushimei.backend.promotion.PromotionService;
import com.sushimei.sushimei.backend.promotion.TemporalPromotionQuoteService;
import com.sushimei.sushimei.backend.promotion.UpdatePromotionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    public PromotionController(PromotionService promotionService,
                               TemporalPromotionQuoteService temporalPromotionQuoteService) {
        this.promotionService = Objects.requireNonNull(promotionService, "promotionService must not be null");
        this.temporalPromotionQuoteService = Objects.requireNonNull(temporalPromotionQuoteService,
                "temporalPromotionQuoteService must not be null");
    }

    @GetMapping
    public List<PromotionResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return promotionService.list(includeInactive);
    }

    @GetMapping("/{id}")
    public PromotionResponse get(@PathVariable Long id) {
        return promotionService.get(id);
    }

    @PostMapping
    public ResponseEntity<PromotionResponse> create(@Valid @RequestBody CreatePromotionRequest request) {
        PromotionResponse created = promotionService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public PromotionResponse update(@PathVariable Long id, @Valid @RequestBody UpdatePromotionRequest request) {
        return promotionService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        promotionService.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/quote")
    public PromotionQuoteResponse quote(@Valid @RequestBody PromotionQuoteRequest request) {
        return temporalPromotionQuoteService.quote(request);
    }
}
