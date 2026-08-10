package com.sushimei.sushimei.backend.catalog;

import java.util.Objects;

public record CatalogTagSummary(Long id, String code, String name, boolean active, int displayOrder) {
    public CatalogTagSummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }

    static CatalogTagSummary from(CatalogTag tag) {
        return new CatalogTagSummary(tag.getId(), tag.getCode(), tag.getName(), tag.isActive(), tag.getDisplayOrder());
    }
}
