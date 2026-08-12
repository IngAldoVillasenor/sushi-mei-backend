package com.sushimei.sushimei.backend.orderread;

/** Immutable promotion evidence persisted with a manual order line, not live promotion data. */
public record OperationalPromotionSnapshotResponse(
        Long id,
        String name,
        String benefitType
) {
}
