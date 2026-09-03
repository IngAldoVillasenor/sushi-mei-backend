package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderLineComponentOmissionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ManualPosOrderReadService {
    private final OrderRepository orderRepository;

    ManualPosOrderReadService(OrderRepository orderRepository) { this.orderRepository = orderRepository; }

    @Transactional(readOnly = true)
    ManualPosOrderResponse existing(java.util.UUID requestId, Long userId, String fingerprint) {
        OrderRecord order = orderRepository.findByClientRequestId(requestId)
                .orElseThrow(() -> new ManualPosOrderException(ManualPosOrderError.ORDER_IDEMPOTENCY_CONFLICT));
        verifyOwnershipAndFingerprint(order, userId, fingerprint);
        return response(order, ManualOrderResult.ALREADY_CREATED);
    }

    static void verifyOwnershipAndFingerprint(OrderRecord order, Long userId, String fingerprint) {
        if (order.getOrderSource() != OrderSource.ANDROID_MANUAL
                || !Objects.equals(order.getCreatedByUserId(), userId)
                || !Objects.equals(order.getRequestFingerprint(), fingerprint)) {
            throw new ManualPosOrderException(ManualPosOrderError.ORDER_IDEMPOTENCY_CONFLICT);
        }
    }

    static ManualPosOrderResponse response(OrderRecord order, ManualOrderResult result) {
        List<ManualPosOrderLineResponse> paid = order.getOrderLines().stream()
                .filter(line -> line.getLineKind() == com.sushimei.sushimei.backend.entity.OrderLineKind.PAID
                        || line.getLineKind() == com.sushimei.sushimei.backend.entity.OrderLineKind.MANUAL_PRICED_LINE)
                .map(line -> lineResponse(line, order.getOrderLines()))
                .toList();
        return new ManualPosOrderResponse(order.getId(), order.getClientRequestId(), result, order.getOrderSource(),
                order.getCreatedByUserId(), order.getFulfillmentType(), order.getPaymentMethod(),
                order.getPaymentTiming(), order.requiresPaymentCollection(), order.getDeliveryAddress(), order.getPickupName(),
                order.getCashDenomination(), order.getPaymentCollectedAt(), order.getPaymentCollectedByUserId(), order.getStatus(),
                asUtcInstant(order.getCreatedAt()), paid, order.getTotalAmountAmount());
    }

    private static Instant asUtcInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static ManualPosOrderLineResponse lineResponse(OrderLineRecord line, List<OrderLineRecord> all) {
        List<ManualPosOrderLineResponse> rewards = line.getLineKind() == com.sushimei.sushimei.backend.entity.OrderLineKind.PAID
                ? all.stream().filter(candidate -> candidate.getLineKind() == com.sushimei.sushimei.backend.entity.OrderLineKind.PROMOTION_REWARD)
                .filter(candidate -> candidate.getSourcePaidLine() != null && Objects.equals(candidate.getSourcePaidLine().getId(), line.getId()))
                .sorted(Comparator.comparing(OrderLineRecord::getRewardOrdinal)).map(candidate -> lineResponse(candidate, List.of())).toList()
                : List.of();
        ManualPromotionSnapshotResponse promotion = line.getAppliedPromotionId() == null ? null
                : new ManualPromotionSnapshotResponse(line.getAppliedPromotionId(), line.getAppliedPromotionName(), line.getAppliedPromotionBenefitType());
        List<ManualOrderSelectionSnapshotResponse> snapshots = line.getSelectionSnapshots().stream()
                .map(snapshot -> snapshotResponse(snapshot)).toList();
        return new ManualPosOrderLineResponse(line.getId(), line.getLineKind(), line.getClientLineKey(), line.getSourceMenuItemId(), line.getDishName(),
                line.getQuantity(), line.getCatalogBaseUnitPrice(), line.getChargedBaseUnitPrice(),
                line.getConfigurationAdjustmentAmount(), line.getUnitPriceAmount(), line.getLineTotalAmount(), promotion,
                line.getRewardOrdinal(), line.getLineNote(), line.getComponentOmissionSnapshots().stream()
                .map(ManualPosOrderReadService::componentOmissionResponse).toList(), snapshots, rewards);
    }

    private static ManualOrderComponentOmissionResponse componentOmissionResponse(OrderLineComponentOmissionSnapshot omission) {
        return new ManualOrderComponentOmissionResponse(omission.getId(), omission.getSourceComponentId(),
                omission.getComponentCode(), omission.getComponentName(), omission.getComponentDetail(),
                omission.getComponentDisplayOrder());
    }

    private static ManualOrderSelectionSnapshotResponse snapshotResponse(OrderLineSelectionSnapshot snapshot) {
        return new ManualOrderSelectionSnapshotResponse(snapshot.getId(),
                snapshot.getParentSelection() == null ? null : snapshot.getParentSelection().getId(), snapshot.getGroupId(),
                snapshot.getGroupName(), snapshot.getSelectionPosition(), snapshot.getSelectedMenuItemId(),
                snapshot.getSelectedItemName(), snapshot.getQuantity(), snapshot.getCatalogUnitPrice(),
                snapshot.getPriceAdjustmentAmount(), snapshot.isDisplayOnTicket(), snapshot.getSelectionNote(),
                snapshot.getComponentOmissionSnapshots().stream().map(omission -> new ManualOrderComponentOmissionResponse(
                        omission.getId(), omission.getSourceComponentId(), omission.getComponentCode(),
                        omission.getComponentName(), omission.getComponentDetail(), omission.getComponentDisplayOrder())).toList());
    }
}
