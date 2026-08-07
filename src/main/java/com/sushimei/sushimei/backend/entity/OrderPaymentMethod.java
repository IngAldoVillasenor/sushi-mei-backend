package com.sushimei.sushimei.backend.entity;

/**
 * Payment metadata stored with an order, independent from conversation state.
 */
public enum OrderPaymentMethod {
    CASH,
    TRANSFER,
    CARD
}
