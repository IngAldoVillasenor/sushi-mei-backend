package com.sushimei.sushimei.backend.catalog;

import java.util.Objects;

public final class CatalogConfigurationException extends RuntimeException {

    private final CatalogDomainError error;

    public CatalogConfigurationException(CatalogDomainError error) {
        super(Objects.requireNonNull(error, "error must not be null").name());
        this.error = error;
    }

    public CatalogDomainError getError() {
        return error;
    }
}
