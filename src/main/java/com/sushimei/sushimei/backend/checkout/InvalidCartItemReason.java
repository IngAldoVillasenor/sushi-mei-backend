package com.sushimei.sushimei.backend.checkout;

public enum InvalidCartItemReason {
    MISSING_CART_ID,
    MISSING_ITEM_ID,
    INVALID_DISH_NAME,
    INVALID_QUANTITY,
    INVALID_UNIT_PRICE,
    LINE_TOTAL_OVERFLOW,
    CART_TOTAL_OVERFLOW
}
