package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import com.sushimei.sushimei.backend.promotion.PromotionRewardConfigurationRequest;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ManualPosOrderService {
    private final OrderRepository orderRepository;
    private final ManualPosOrderFingerprint fingerprint;
    private final ManualPosOrderCreationTransaction creationTransaction;
    private final ManualPosOrderReadService readService;
    private final CheckoutMoney checkoutMoney;

    public ManualPosOrderService(OrderRepository orderRepository,
                                 ManualPosOrderFingerprint fingerprint,
                                 ManualPosOrderCreationTransaction creationTransaction,
                                 ManualPosOrderReadService readService,
                                 CheckoutMoney checkoutMoney) {
        this.orderRepository = orderRepository;
        this.fingerprint = fingerprint;
        this.creationTransaction = creationTransaction;
        this.readService = readService;
        this.checkoutMoney = checkoutMoney;
    }

    public ManualPosOrderResponse create(Long authenticatedUserId, ManualPosOrderRequest request) {
        if (authenticatedUserId == null || authenticatedUserId <= 0 || request == null || request.requestId() == null
                || request.fulfillmentType() == null
                || (request.lines().isEmpty() && request.manualLines().isEmpty())) {
            throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID);
        }
        OrderPaymentTiming paymentTiming = request.paymentTiming() == null
                ? OrderPaymentTiming.IMMEDIATE
                : request.paymentTiming();
        String deliveryAddress = normalizeOptional(request.deliveryAddress());
        String pickupName = normalizeOptional(request.pickupName());
        BigDecimal cashDenomination = validateFulfillmentAndPayment(request.fulfillmentType(), paymentTiming,
                request.paymentMethod(), deliveryAddress, pickupName, request.cashDenomination());
        List<PromotionQuoteLineRequest> lines = normalizeLines(request.lines());
        List<NormalizedManualPricedLine> manualLines = normalizeManualLines(request.manualLines());
        ensureDistinctLineKeys(lines, manualLines);
        String canonicalFingerprint = fingerprint.fingerprint(request.fulfillmentType(), request.paymentMethod(), paymentTiming,
                deliveryAddress, pickupName, cashDenomination, lines, manualLines);
        NormalizedManualPosOrder normalized = new NormalizedManualPosOrder(request.requestId(), request.fulfillmentType(),
                request.paymentMethod(), paymentTiming, deliveryAddress, pickupName, cashDenomination, lines, manualLines,
                canonicalFingerprint);
        if (orderRepository.findByClientRequestId(request.requestId()).isPresent()) {
            return readService.existing(request.requestId(), authenticatedUserId, canonicalFingerprint);
        }
        try {
            return creationTransaction.create(authenticatedUserId, normalized);
        } catch (DataIntegrityViolationException exception) {
            // The REQUIRES_NEW creation transaction has rolled back. A concurrent winner can now be read safely.
            if (orderRepository.findByClientRequestId(request.requestId()).isPresent()) {
                return readService.existing(request.requestId(), authenticatedUserId, canonicalFingerprint);
            }
            throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception);
        }
    }

    private BigDecimal validateFulfillmentAndPayment(OrderFulfillmentType fulfillment, OrderPaymentTiming paymentTiming,
                                                     OrderPaymentMethod payment,
                                                     String deliveryAddress, String pickupName, BigDecimal cash) {
        if (fulfillment == OrderFulfillmentType.DELIVERY) {
            if (!bounded(deliveryAddress, 5, 500) || pickupName != null) throw invalid();
        } else if (fulfillment == OrderFulfillmentType.PICKUP) {
            if (!bounded(pickupName, 2, 120) || deliveryAddress != null) throw invalid();
        } else throw invalid();
        if (paymentTiming == OrderPaymentTiming.ON_DELIVERY) {
            if (fulfillment != OrderFulfillmentType.DELIVERY || payment != null || cash != null) {
                throw invalid();
            }
            return null;
        }
        if (paymentTiming != OrderPaymentTiming.IMMEDIATE || payment == null) throw invalid();
        if (fulfillment == OrderFulfillmentType.PICKUP) {
            if (payment != OrderPaymentMethod.CASH && payment != OrderPaymentMethod.TRANSFER
                    && payment != OrderPaymentMethod.CARD) {
                throw invalid();
            }
            // Pickup payment is settled at the counter; any legacy denomination is operationally irrelevant.
            return null;
        }
        if (payment == OrderPaymentMethod.CASH) {
            try { return checkoutMoney.normalizeNumericAmount(cash); }
            catch (IllegalArgumentException exception) { throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception); }
        }
        if (payment != OrderPaymentMethod.TRANSFER || cash != null) throw invalid();
        return null;
    }

    private static String normalizeOptional(String value) { if (value == null) return null; String trimmed=value.trim(); return trimmed.isEmpty()?null:trimmed; }
    private List<PromotionQuoteLineRequest> normalizeLines(List<PromotionQuoteLineRequest> lines) {
        return lines.stream().map(line -> {
            if (line == null) throw invalid();
            return new PromotionQuoteLineRequest(normalizeLineKey(line.lineKey()), line.menuItemId(), line.quantity(), normalizeGroups(line.groups()),
                    normalizeRewardConfigurations(line.rewardConfigurations()), normalizeComponentIds(line.omittedComponentIds()),
                    normalizeNote(line.note()));
        }).toList();
    }

    private List<PromotionRewardConfigurationRequest> normalizeRewardConfigurations(
            List<PromotionRewardConfigurationRequest> configurations) {
        return configurations.stream().map(configuration -> {
            if (configuration == null) throw invalid();
            return new PromotionRewardConfigurationRequest(configuration.rewardOrdinal(), configuration.menuItemId(),
                    normalizeGroups(configuration.groups()), normalizeComponentIds(configuration.omittedComponentIds()),
                    normalizeNote(configuration.note()));
        }).toList();
    }

    private List<NormalizedManualPricedLine> normalizeManualLines(List<ManualPricedLineRequest> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        return lines.stream().map(line -> {
            if (line == null) throw invalid();
            String key = normalizeLineKey(line.lineKey());
            String description = normalizeDescription(line.description());
            int quantity;
            BigDecimal unit;
            BigDecimal total;
            try {
                quantity = checkoutMoney.requirePositiveQuantity(line.quantity());
                unit = checkoutMoney.normalizeNumericAmount(line.unitAmount());
                total = checkoutMoney.normalizeNumericAmount(unit.multiply(BigDecimal.valueOf(quantity)));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception);
            }
            return new NormalizedManualPricedLine(key, description, quantity, unit, total);
        }).toList();
    }

    private void ensureDistinctLineKeys(List<PromotionQuoteLineRequest> catalogLines,
                                        List<NormalizedManualPricedLine> manualLines) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (PromotionQuoteLineRequest line : catalogLines) if (!keys.add(line.lineKey())) throw invalid();
        for (NormalizedManualPricedLine line : manualLines) if (!keys.add(line.lineKey())) throw invalid();
    }

    private List<com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest> normalizeGroups(
            List<com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest> groups) {
        if (groups == null || groups.isEmpty()) return List.of();
        return groups.stream().map(group -> {
            if (group == null) throw invalid();
            List<com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionRequest> selections = group.selections().stream()
                    .map(selection -> {
                        if (selection == null) throw invalid();
                        return new com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionRequest(selection.menuItemId(),
                                selection.quantity(), normalizeGroups(selection.groups()),
                                normalizeComponentIds(selection.omittedComponentIds()), normalizeNote(selection.note()));
                    }).toList();
            return new com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest(group.groupId(), selections);
        }).toList();
    }

    private List<Long> normalizeComponentIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.stream().anyMatch(id -> id == null || id <= 0) || ids.stream().distinct().count() != ids.size()) throw invalid();
        return ids.stream().sorted().toList();
    }

    private String normalizeLineKey(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null || normalized.length() > 120) throw invalid();
        return normalized;
    }

    private String normalizeNote(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 500) throw invalid();
        return normalized;
    }
    private String normalizeDescription(String value) {
        String normalized = normalizeNote(value);
        if (normalized == null) throw invalid();
        return normalized;
    }
    private static boolean bounded(String value, int minimum, int maximum) { return value != null && value.length() >= minimum && value.length() <= maximum; }
    private static ManualPosOrderException invalid() { return new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID); }
}
