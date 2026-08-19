package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stable evidence view for one persisted operational order. */
public record OperationalOrderDetailResponse(
        Long id,
        String externalOrderId,
        String externalReference,
        UUID requestId,
        OrderSource orderSource,
        Long createdByUserId,
        OrderFulfillmentType fulfillmentType,
        OrderPaymentMethod paymentMethod,
        String deliveryAddress,
        String pickupName,
        BigDecimal cashDenomination,
        String phoneNumber,
        String transferReceiptPath,
        String paymentNotes,
        String status,
        Instant createdAt,
        BigDecimal total,
        String legacyOrderDetails,
        List<OperationalOrderLineResponse> lines
) {
    public OperationalOrderDetailResponse {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
