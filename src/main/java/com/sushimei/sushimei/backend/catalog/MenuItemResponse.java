package com.sushimei.sushimei.backend.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record MenuItemResponse(
        Long id,
        String name,
        String description,
        String category,
        BigDecimal price,
        boolean active,
        boolean available,
        boolean standaloneOrderable,
        int displayOrder,
        List<CatalogTagSummary> tags,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public MenuItemResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(price, "price must not be null");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags must not be null"));
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static MenuItemResponse from(MenuItem item) {
        List<CatalogTagSummary> tags = item.getTags().stream()
                .map(CatalogTagSummary::from)
                .sorted(Comparator.comparingInt(CatalogTagSummary::displayOrder)
                        .thenComparing(CatalogTagSummary::code)
                        .thenComparing(CatalogTagSummary::id))
                .toList();
        return new MenuItemResponse(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getCategory(),
                item.getPriceAmount(),
                item.isActive(),
                item.isAvailable(),
                item.isStandaloneOrderable(),
                item.getDisplayOrder(),
                tags,
                item.getVersion(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
