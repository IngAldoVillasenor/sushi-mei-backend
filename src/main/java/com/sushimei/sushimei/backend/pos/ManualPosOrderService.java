package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
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
                || request.fulfillmentType() == null || request.paymentMethod() == null || request.lines().isEmpty()) {
            throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID);
        }
        String deliveryAddress = normalizeOptional(request.deliveryAddress());
        String pickupName = normalizeOptional(request.pickupName());
        BigDecimal cashDenomination = validateFulfillmentAndPayment(request.fulfillmentType(), request.paymentMethod(),
                deliveryAddress, pickupName, request.cashDenomination());
        List<PromotionQuoteLineRequest> lines = normalizeLines(request.lines());
        String canonicalFingerprint = fingerprint.fingerprint(request.fulfillmentType(), request.paymentMethod(),
                deliveryAddress, pickupName, cashDenomination, lines);
        NormalizedManualPosOrder normalized = new NormalizedManualPosOrder(request.requestId(), request.fulfillmentType(),
                request.paymentMethod(), deliveryAddress, pickupName, cashDenomination, lines, canonicalFingerprint);
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

    private BigDecimal validateFulfillmentAndPayment(OrderFulfillmentType fulfillment, OrderPaymentMethod payment,
                                                     String deliveryAddress, String pickupName, BigDecimal cash) {
        if (fulfillment == OrderFulfillmentType.DELIVERY) {
            if (!bounded(deliveryAddress, 5, 500) || pickupName != null) throw invalid();
        } else if (fulfillment == OrderFulfillmentType.PICKUP) {
            if (!bounded(pickupName, 2, 120) || deliveryAddress != null) throw invalid();
        } else throw invalid();
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
            return new PromotionQuoteLineRequest(normalizeLineKey(line.lineKey()), line.menuItemId(), line.quantity(), line.groups(),
                    normalizeRewardConfigurations(line.rewardConfigurations()), normalizeComponentIds(line.omittedComponentIds()),
                    normalizeNote(line.note()));
        }).toList();
    }

    private List<PromotionRewardConfigurationRequest> normalizeRewardConfigurations(
            List<PromotionRewardConfigurationRequest> configurations) {
        return configurations.stream().map(configuration -> {
            if (configuration == null) throw invalid();
            return new PromotionRewardConfigurationRequest(configuration.rewardOrdinal(), configuration.menuItemId(),
                    configuration.groups());
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
    private static boolean bounded(String value, int minimum, int maximum) { return value != null && value.length() >= minimum && value.length() <= maximum; }
    private static ManualPosOrderException invalid() { return new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID); }
}
