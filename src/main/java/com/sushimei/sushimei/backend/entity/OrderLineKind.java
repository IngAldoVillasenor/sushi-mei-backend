package com.sushimei.sushimei.backend.entity;

/**
 * Stable technical classification of persisted order evidence. Product and
 * promotion eligibility remain catalog data; this only identifies paid versus
 * backend-generated reward lines.
 */
public enum OrderLineKind {
    PAID,
    PROMOTION_REWARD,
    /** Explicit non-catalog physical POS revenue entered through the Open Sale command. */
    OPEN_SALE
}
