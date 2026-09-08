package com.cardovia.merkon.backend.catalog;

public class MenuCatalogVersionConflictException extends RuntimeException {

    public MenuCatalogVersionConflictException() {
        super("Menu item version conflicts with the current value.");
    }
}
