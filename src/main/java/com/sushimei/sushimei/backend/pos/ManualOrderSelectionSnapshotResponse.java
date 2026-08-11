package com.sushimei.sushimei.backend.pos;

import java.math.BigDecimal;

/** Flat, parent-linked immutable configuration evidence for an order line. */
public record ManualOrderSelectionSnapshotResponse(
        Long id,
        Long parentSelectionSnapshotId,
        Long groupId,
        String groupName,
        int selectionPosition,
        Long menuItemId,
        String itemName,
        int quantity,
        BigDecimal catalogUnitPrice,
        BigDecimal priceAdjustment
) {
}
