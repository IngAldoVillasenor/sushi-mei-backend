package com.cardovia.merkon.backend.order;

/** Database-only result used by the legacy controller before its external orchestration begins. */
public record LegacyOrderRejectionResult(Long orderId, String phoneNumber) {
}
