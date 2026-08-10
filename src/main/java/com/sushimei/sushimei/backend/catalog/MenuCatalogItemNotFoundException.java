package com.sushimei.sushimei.backend.catalog;

public class MenuCatalogItemNotFoundException extends RuntimeException {

    public MenuCatalogItemNotFoundException() {
        super("Menu item was not found.");
    }
}
