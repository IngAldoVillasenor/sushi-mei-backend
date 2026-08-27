package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderLineKind;
import java.math.BigDecimal;
import java.util.List;

public record ManualPosOrderLineResponse(
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
        ManualPromotionSnapshotResponse promotion,
        Integer rewardOrdinal,
        String note,
        List<ManualOrderComponentOmissionResponse> omittedComponents,
        List<ManualOrderSelectionSnapshotResponse> configuration,
        List<ManualPosOrderLineResponse> rewards
) {
    public ManualPosOrderLineResponse {
        omittedComponents = List.copyOf(omittedComponents == null ? List.of() : omittedComponents);
        configuration = List.copyOf(configuration == null ? List.of() : configuration);
        rewards = List.copyOf(rewards == null ? List.of() : rewards);
    }
}
