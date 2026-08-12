package com.sushimei.sushimei.backend.order;

import java.util.List;

/** Typed lifecycle vocabulary while the legacy database column remains a String. */
public enum OrderLifecycleStatus {
    PENDING_VALIDATION,
    PENDING,
    PREPARING,
    READY,
    COMPLETED,
    CANCELLED_CLARIFICATION;

    private static final List<String> ACTIVE_PERSISTED_VALUES = List.of(
            PENDING_VALIDATION.name(), PENDING.name(), PREPARING.name(), READY.name());

    public static OrderLifecycleStatus fromPersisted(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Order lifecycle status is absent");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported persisted order lifecycle status", exception);
        }
    }

    public static List<String> activePersistedValues() {
        return ACTIVE_PERSISTED_VALUES;
    }

    public String persistedValue() {
        return name();
    }
}
