package com.cardovia.merkon.backend.order;

public record OrderLifecycleTransitionResult(
        Long orderId,
        OrderLifecycleStatus previousStatus,
        OrderLifecycleStatus currentStatus
) {
}
