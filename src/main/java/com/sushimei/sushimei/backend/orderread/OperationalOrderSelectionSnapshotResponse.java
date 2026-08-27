package com.sushimei.sushimei.backend.orderread;

import java.math.BigDecimal;
import java.util.List;

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
        boolean displayOnTicket,
        String note,
        List<OperationalOrderComponentOmissionResponse> omittedComponents
) {
    public OperationalOrderSelectionSnapshotResponse {
        omittedComponents = List.copyOf(omittedComponents == null ? List.of() : omittedComponents);
    }
}
