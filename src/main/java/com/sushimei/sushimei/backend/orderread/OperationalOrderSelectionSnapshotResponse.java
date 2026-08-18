package com.sushimei.sushimei.backend.orderread;

import java.math.BigDecimal;

/** Flat parent-linked selection evidence; parentSelectionSnapshotId preserves recursive configuration. */
public record OperationalOrderSelectionSnapshotResponse(
        Long id,
        Long parentSelectionSnapshotId,
        Long groupId,
        String groupName,
        int selectionPosition,
        Long menuItemId,
        String itemName,
        int quantity,
        BigDecimal catalogUnitPrice,
        BigDecimal priceAdjustment,
        boolean displayOnTicket
) {
}
