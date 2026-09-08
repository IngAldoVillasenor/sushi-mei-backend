package com.cardovia.merkon.backend.entity;

/**
 * Payment metadata stored with an order, independent from conversation state.
 */
public enum OrderPaymentMethod {
    CASH,
    TRANSFER,
    CARD
}
