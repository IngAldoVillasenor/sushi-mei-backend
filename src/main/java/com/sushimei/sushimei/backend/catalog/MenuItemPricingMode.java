package com.sushimei.sushimei.backend.catalog;

/**
 * Generic catalog pricing behavior. Product classifications remain catalog data;
 * this enum only describes how a configured root contributes its own base price.
 */
public enum MenuItemPricingMode {
    BASE_PLUS_ADJUSTMENTS,
    SELECTION_SUM
}
