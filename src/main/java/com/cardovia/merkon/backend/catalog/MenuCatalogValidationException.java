package com.cardovia.merkon.backend.catalog;

public class MenuCatalogValidationException extends RuntimeException {

    public MenuCatalogValidationException() {
        super("Menu catalog input is invalid.");
    }
}
