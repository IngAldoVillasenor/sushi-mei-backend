package com.sushimei.sushimei.backend.pos;

import java.math.BigDecimal;
import java.util.List;

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
        BigDecimal priceAdjustment,
        boolean displayOnTicket,
        String note,
        List<ManualOrderComponentOmissionResponse> omittedComponents
) {
    public ManualOrderSelectionSnapshotResponse {
        omittedComponents = List.copyOf(omittedComponents == null ? List.of() : omittedComponents);
    }
}
