package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderLineComponentOmissionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.repository.OrderLineSelectionSnapshotRepository;
import com.sushimei.sushimei.backend.repository.OrderLineComponentOmissionSnapshotRepository;
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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.sushimei.sushimei.backend.entity.OrderSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

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
    private final OrderLineComponentOmissionSnapshotRepository omissionSnapshotRepository;
    private final ParallelMoneyResolver parallelMoneyResolver;

    public OperationalOrderReadService(OrderRepository orderRepository,
                                       OrderLineSelectionSnapshotRepository selectionSnapshotRepository,
                                       OrderLineComponentOmissionSnapshotRepository omissionSnapshotRepository,
                                       ParallelMoneyResolver parallelMoneyResolver) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.selectionSnapshotRepository = Objects.requireNonNull(selectionSnapshotRepository,
                "selectionSnapshotRepository must not be null");
        this.omissionSnapshotRepository = Objects.requireNonNull(omissionSnapshotRepository,
                "omissionSnapshotRepository must not be null");
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
        Set<Long> structuredOrderIds = orderIds.isEmpty() ? java.util.Set.of() : new java.util.HashSet<>(orderRepository.findIdsWithOrderLines(orderIds));
        return orders.stream()
                .map(order -> summary(order, structuredOrderIds.contains(order.getId())))
                .toList();
    }


    @Transactional(readOnly = true)
    public HistoricalAnalyticsResponse historicalAnalytics(Instant from, Instant to) {
        if (from != null && to != null && (from.isAfter(to) || from.equals(to))) {
            throw new InvalidDateRangeException("Invalid date range: from must be strictly before to");
        }

        LocalDateTime fromLdt = from != null ? LocalDateTime.ofInstant(from, ZoneOffset.UTC) : null;
        LocalDateTime toLdt = to != null ? LocalDateTime.ofInstant(to, ZoneOffset.UTC) : null;

        long completedOrderCount = orderRepository.countCompletedOrders(fromLdt, toLdt);
        long voidedOrderCount = orderRepository.countVoidedOrders(fromLdt, toLdt);

        java.math.BigDecimal completedRevenue = java.math.BigDecimal.ZERO;
        java.math.BigDecimal averageCompletedTicket = java.math.BigDecimal.ZERO;

        if (completedOrderCount > 0) {
            java.math.BigDecimal sum = orderRepository.sumCompletedRevenue(fromLdt, toLdt);
            if (sum != null) {
                completedRevenue = sum;
            }
            averageCompletedTicket = completedRevenue.divide(new java.math.BigDecimal(completedOrderCount), 2, java.math.RoundingMode.HALF_UP);
        }

        java.util.List<SalesBySourceResponse> salesBySource = orderRepository.aggregateCompletedSalesBySource(fromLdt, toLdt);

        return new HistoricalAnalyticsResponse(
                from,
                to,
                completedRevenue,
                completedOrderCount,
                averageCompletedTicket,
                voidedOrderCount,
                salesBySource
        );
    }

    @Transactional(readOnly = true)
    public Page<HistoricalOrderSummaryResponse> historicalOrders(Instant from, Instant to, OrderSource source, String status, int page, int size) {
        if (page < 0) page = 0;
        if (size > 100) size = 100;
        if (size < 1) size = 50;
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

        LocalDateTime fromLdt = from != null ? LocalDateTime.ofInstant(from, ZoneOffset.UTC) : null;
        LocalDateTime toLdt = to != null ? LocalDateTime.ofInstant(to, ZoneOffset.UTC) : null;

                Specification<OrderRecord> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (fromLdt != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromLdt));
            }
            if (toLdt != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toLdt));
            }
            if (source != null) {
                predicates.add(cb.equal(root.get("orderSource"), source));
            }
            if (status != null && !status.isEmpty()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<OrderRecord> orders = orderRepository.findAll(spec, pageable);

        List<Long> orderIds = orders.stream().map(OrderRecord::getId).toList();
        Set<Long> structuredOrderIds = orderIds.isEmpty() ? java.util.Set.of() : new java.util.HashSet<>(orderRepository.findIdsWithOrderLines(orderIds));

        return orders.map(order -> new HistoricalOrderSummaryResponse(
                order.getId(),
                order.getExternalOrderId(),
                order.getExternalReference(),
                order.getOrderSource(),
                order.getStatus(),
                order.getFulfillmentType(),
                order.getPaymentMethod(),
                order.getPickupName(),
                total(order),
                asUtcInstant(order.getCreatedAt()),
                structuredOrderIds.contains(order.getId())
        ));
    }

    public OperationalOrderDetailResponse order(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new OperationalOrderReadException();
        }
        OrderRecord order = orderRepository.findOperationalDetailById(orderId)
                .orElseThrow(OperationalOrderReadException::new);
        Map<Long, List<OrderLineSelectionSnapshot>> snapshotsByLine = snapshotsByLine(order.getId());
        Map<Long, List<OrderLineComponentOmissionSnapshot>> omissionsByLine = omissionsByLine(order.getId());
        List<OperationalOrderLineResponse> lines = order.getOrderLines().stream()
                .sorted(ORDER_LINE_ORDER)
                .map(line -> line(line, snapshotsByLine.getOrDefault(line.getId(), List.of()),
                        omissionsByLine.getOrDefault(line.getId(), List.of())))
                .toList();
        return new OperationalOrderDetailResponse(
                order.getId(),
                order.getExternalOrderId(),
                order.getExternalReference(),
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

    private Map<Long, List<OrderLineComponentOmissionSnapshot>> omissionsByLine(Long orderId) {
        Map<Long, List<OrderLineComponentOmissionSnapshot>> omissionsByLine = new HashMap<>();
        for (OrderLineComponentOmissionSnapshot omission : omissionSnapshotRepository.findByOrderIdForOperationalRead(orderId)) {
            omissionsByLine.computeIfAbsent(omission.getOrderLineId(), ignored -> new ArrayList<>()).add(omission);
        }
        return omissionsByLine;
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
                                               List<OrderLineSelectionSnapshot> snapshots,
                                               List<OrderLineComponentOmissionSnapshot> omissions) {
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
                line.getExternalProductReference(),
                line.isExternalHistorical(),
                line.getExternalProductDetail(),
                line.getSourceUnitPriceAmount(),
                line.getSourceLineTotalAmount(),
                line.getSourceDiscountAmount(),
                line.getSourceDiscountPercentage(),
                line.getSourceTaxAmount(),
                line.getSourcePriceIncludingTaxAmount(),
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
                line.getLineNote(),
                omissions.stream().map(this::componentOmission).toList(),
                configuration);
    }

    private OperationalOrderComponentOmissionResponse componentOmission(OrderLineComponentOmissionSnapshot omission) {
        return new OperationalOrderComponentOmissionResponse(omission.getId(), omission.getSourceComponentId(),
                omission.getComponentCode(), omission.getComponentName(), omission.getComponentDetail(),
                omission.getComponentDisplayOrder());
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
        return order.getOrderSource() == OrderSource.VENDIS_IMPORT
                ? parallelMoneyResolver.resolveExternalHistorical(order.getTotalAmountAmount(), order.getTotalAmount())
                : parallelMoneyResolver.resolve(order.getTotalAmountAmount(), order.getTotalAmount());
    }

    private boolean requiresPaymentValidation(OrderRecord order) {
        return OrderLifecycleStatus.PENDING_VALIDATION.persistedValue().equals(order.getStatus())
                && order.getPaymentMethod() == OrderPaymentMethod.TRANSFER;
    }

    private Instant asUtcInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
