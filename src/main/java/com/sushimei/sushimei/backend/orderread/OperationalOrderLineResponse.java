package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.entity.OrderLineKind;
import java.math.BigDecimal;
import java.util.List;

/** Immutable persisted line evidence. Reward lines remain explicit and link to their paid source. */
public record OperationalOrderLineResponse(
        Long id,
        OrderLineKind lineKind,
        String lineKey,
        Long sourceMenuItemId,
        String name,
        int quantity,
        BigDecimal catalogBaseUnitPrice,
        BigDecimal chargedBaseUnitPrice,
        BigDecimal configurationAdjustmentAmount,
        BigDecimal finalUnitAmount,
        BigDecimal finalLineTotal,
        OperationalPromotionSnapshotResponse promotion,
        Integer rewardOrdinal,
        Long sourcePaidLineId,
        List<OperationalOrderSelectionSnapshotResponse> configuration
) {
    public OperationalOrderLineResponse {
        configuration = List.copyOf(configuration == null ? List.of() : configuration);
    }
}
