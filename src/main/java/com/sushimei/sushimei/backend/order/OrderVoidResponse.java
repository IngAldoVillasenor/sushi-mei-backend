package com.sushimei.sushimei.backend.order;

import java.time.Instant;

/** Persisted result of an authoritative physical POS order void. */
public record OrderVoidResponse(
        Long orderId,
        OrderLifecycleStatus previousStatus,
        OrderLifecycleStatus currentStatus,
        String voidReason,
        Instant voidedAt,
        Long voidedByUserId
) {
}
