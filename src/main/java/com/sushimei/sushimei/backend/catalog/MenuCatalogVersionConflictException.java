package com.sushimei.sushimei.backend.catalog;

public class MenuCatalogVersionConflictException extends RuntimeException {

    public MenuCatalogVersionConflictException() {
        super("Menu item version conflicts with the current value.");
    }
}
