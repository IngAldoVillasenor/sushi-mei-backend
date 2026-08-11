package com.sushimei.sushimei.backend.order;

public record OrderLifecycleTransitionResult(
        Long orderId,
        OrderLifecycleStatus previousStatus,
        OrderLifecycleStatus currentStatus
) {
}
