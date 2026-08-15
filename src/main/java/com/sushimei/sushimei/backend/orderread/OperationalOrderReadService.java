package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.repository.OrderLineSelectionSnapshotRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only operational API boundary. It reads persisted order evidence only and never invokes
 * catalog resolution, promotion quoting, lifecycle transitions, or external integrations.
 */
@Service
public class OperationalOrderReadService {

    private static final Comparator<OrderLineRecord> ORDER_LINE_ORDER = Comparator
            .comparingInt(OrderLineRecord::getLinePosition)
            .thenComparing(OrderLineRecord::getId);

    private final OrderRepository orderRepository;
    private final OrderLineSelectionSnapshotRepository selectionSnapshotRepository;
    private final ParallelMoneyResolver parallelMoneyResolver;

    public OperationalOrderReadService(OrderRepository orderRepository,
                                       OrderLineSelectionSnapshotRepository selectionSnapshotRepository,
                                       ParallelMoneyResolver parallelMoneyResolver) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.selectionSnapshotRepository = Objects.requireNonNull(selectionSnapshotRepository,
                "selectionSnapshotRepository must not be null");
        this.parallelMoneyResolver = Objects.requireNonNull(parallelMoneyResolver,
                "parallelMoneyResolver must not be null");
    }

    @Transactional(readOnly = true)
    public List<OperationalOrderSummaryResponse> activeOrders() {
        List<OrderRecord> orders = orderRepository.findByStatusInOrderByCreatedAtAscIdAsc(
                OrderLifecycleStatus.activePersistedValues());
        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream().map(OrderRecord::getId).toList();
        Set<Long> structuredOrderIds = new HashSet<>(orderRepository.findIdsWithOrderLines(orderIds));
        return orders.stream()
                .map(order -> summary(order, structuredOrderIds.contains(order.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public OperationalOrderDetailResponse order(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new OperationalOrderReadException();
        }
        OrderRecord order = orderRepository.findOperationalDetailById(orderId)
                .orElseThrow(OperationalOrderReadException::new);
        Map<Long, List<OrderLineSelectionSnapshot>> snapshotsByLine = snapshotsByLine(order.getId());
        List<OperationalOrderLineResponse> lines = order.getOrderLines().stream()
                .sorted(ORDER_LINE_ORDER)
                .map(line -> line(line, snapshotsByLine.getOrDefault(line.getId(), List.of())))
                .toList();
        return new OperationalOrderDetailResponse(
                order.getId(),
                order.getClientRequestId(),
                order.getOrderSource(),
                order.getCreatedByUserId(),
                order.getFulfillmentType(),
                order.getPaymentMethod(),
                order.getDeliveryAddress(),
                order.getPickupName(),
                order.getCashDenomination(),
                order.getPhoneNumber(),
                order.getTransferReceiptPath(),
                order.getPaymentNotes(),
                order.getStatus(),
                asUtcInstant(order.getCreatedAt()),
                total(order),
                order.getOrderDetails(),
                lines);
    }

    private Map<Long, List<OrderLineSelectionSnapshot>> snapshotsByLine(Long orderId) {
        Map<Long, List<OrderLineSelectionSnapshot>> snapshotsByLine = new HashMap<>();
        for (OrderLineSelectionSnapshot snapshot : selectionSnapshotRepository.findByOrderIdForOperationalRead(orderId)) {
            snapshotsByLine.computeIfAbsent(snapshot.getOrderLineId(), ignored -> new ArrayList<>()).add(snapshot);
        }
        return snapshotsByLine;
    }

    private OperationalOrderSummaryResponse summary(OrderRecord order, boolean structuredLinesAvailable) {
        return new OperationalOrderSummaryResponse(
                order.getId(),
                order.getOrderSource(),
                order.getStatus(),
                order.getFulfillmentType(),
                order.getPaymentMethod(),
                order.getDeliveryAddress(),
                order.getPickupName(),
                order.getCashDenomination(),
                order.getPhoneNumber(),
                total(order),
                asUtcInstant(order.getCreatedAt()),
                requiresPaymentValidation(order),
                structuredLinesAvailable);
    }

    private OperationalOrderLineResponse line(OrderLineRecord line,
                                               List<OrderLineSelectionSnapshot> snapshots) {
        OperationalPromotionSnapshotResponse promotion = line.getAppliedPromotionId() == null
                ? null
                : new OperationalPromotionSnapshotResponse(
                        line.getAppliedPromotionId(),
                        line.getAppliedPromotionName(),
                        line.getAppliedPromotionBenefitType());
        List<OperationalOrderSelectionSnapshotResponse> configuration = snapshots.stream()
                .map(this::snapshot)
                .toList();
        return new OperationalOrderLineResponse(
                line.getId(),
                line.getLineKind(),
                line.getClientLineKey(),
                line.getSourceMenuItemId(),
                line.getDishName(),
                line.getQuantity(),
                line.getCatalogBaseUnitPrice(),
                line.getChargedBaseUnitPrice(),
                line.getConfigurationAdjustmentAmount(),
                line.getUnitPriceAmount(),
                line.getLineTotalAmount(),
                promotion,
                line.getRewardOrdinal(),
                line.getSourcePaidLine() == null ? null : line.getSourcePaidLine().getId(),
                configuration);
    }

    private OperationalOrderSelectionSnapshotResponse snapshot(OrderLineSelectionSnapshot snapshot) {
        return new OperationalOrderSelectionSnapshotResponse(
                snapshot.getId(),
                snapshot.getParentSelection() == null ? null : snapshot.getParentSelection().getId(),
                snapshot.getGroupId(),
                snapshot.getGroupName(),
                snapshot.getSelectionPosition(),
                snapshot.getSelectedMenuItemId(),
                snapshot.getSelectedItemName(),
                snapshot.getQuantity(),
                snapshot.getCatalogUnitPrice(),
                snapshot.getPriceAdjustmentAmount(),
                snapshot.isDisplayOnTicket());
    }

    private BigDecimal total(OrderRecord order) {
        if (order.getTotalAmountAmount() == null && order.getTotalAmount() == null) {
            return null;
        }
        return parallelMoneyResolver.resolve(order.getTotalAmountAmount(), order.getTotalAmount());
    }

    private boolean requiresPaymentValidation(OrderRecord order) {
        return OrderLifecycleStatus.PENDING_VALIDATION.persistedValue().equals(order.getStatus())
                && order.getPaymentMethod() == OrderPaymentMethod.TRANSFER;
    }

    private Instant asUtcInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
