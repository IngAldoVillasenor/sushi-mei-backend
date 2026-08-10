package com.sushimei.sushimei.backend.catalog;

import java.time.Instant;
import java.util.Objects;

public record CatalogTagResponse(
        Long id,
        String code,
        String name,
        boolean active,
        int displayOrder,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public CatalogTagResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static CatalogTagResponse from(CatalogTag tag) {
        return new CatalogTagResponse(tag.getId(), tag.getCode(), tag.getName(), tag.isActive(),
                tag.getDisplayOrder(), tag.getVersion(), tag.getCreatedAt(), tag.getUpdatedAt());
    }
}
