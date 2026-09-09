package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.businessday.BusinessDayError;
import com.cardovia.merkon.backend.businessday.BusinessDayException;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import com.cardovia.merkon.backend.checkout.CheckoutMoney;
import com.cardovia.merkon.backend.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Dedicated boundary for entered-price counter revenue; normal catalog POS remains price-free. */
@Service
public class OpenSaleService {
    private final OpenSaleFingerprint fingerprint;
    private final OpenSaleCreationTransaction creationTransaction;
    private final OrderRepository orderRepository;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;
    private final LegacyBusinessResolver legacyBusinessResolver;

    public OpenSaleService(OpenSaleFingerprint fingerprint,
                           OpenSaleCreationTransaction creationTransaction,
                           OrderRepository orderRepository,
                           CheckoutMoney checkoutMoney,
                           Clock clock,
                           LegacyBusinessResolver legacyBusinessResolver) {
        this.fingerprint = fingerprint;
        this.creationTransaction = creationTransaction;
        this.orderRepository = orderRepository;
        this.checkoutMoney = checkoutMoney;
        this.clock = clock;
        this.legacyBusinessResolver = legacyBusinessResolver;
    }

    public OpenSaleResponse create(Long authenticatedUserId, OpenSaleRequest request) {
        return create(legacyBusinessResolver.requireLegacyBusinessId(), authenticatedUserId, request);
    }

    public OpenSaleResponse create(Long businessId, Long authenticatedUserId, OpenSaleRequest request) {
        if (authenticatedUserId == null || authenticatedUserId <= 0 || request == null || request.requestId() == null
                || request.paymentMethod() == null) {
            throw invalid();
        }
        String description = normalizeDescription(request.description());
        BigDecimal amount = positive(request.amount());
        BigDecimal cashDenomination = normalizeCashDenomination(request.paymentMethod(), request.cashDenomination(), amount);
        String requestFingerprint = fingerprint.fingerprint(description, amount, request.paymentMethod(), cashDenomination);
        NormalizedOpenSale normalized = new NormalizedOpenSale(request.requestId(), description, amount,
                request.paymentMethod(), cashDenomination, requestFingerprint);
        if (orderRepository.findByBusinessIdAndClientRequestId(businessId, request.requestId()).isPresent()) {
            return existing(businessId, normalized, authenticatedUserId);
        }
        try {
            return creationTransaction.create(businessId, authenticatedUserId, normalized, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            if (orderRepository.findByBusinessIdAndClientRequestId(businessId, request.requestId()).isPresent()) {
                return existing(businessId, normalized, authenticatedUserId);
            }
            throw new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID, exception);
        } catch (BusinessDayException exception) {
            if (exception.getError() == BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED) {
                throw new OpenSaleException(OpenSaleError.OPEN_SALE_BUSINESS_DAY_OPEN_REQUIRED, exception);
            }
            throw new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID, exception);
        }
    }

    private OpenSaleResponse existing(Long businessId, NormalizedOpenSale request, Long userId) {
        return OpenSaleCreationTransaction.existing(orderRepository.findByBusinessIdAndClientRequestIdWithOrderLines(businessId, request.requestId())
                .orElseThrow(() -> new OpenSaleException(OpenSaleError.OPEN_SALE_IDEMPOTENCY_CONFLICT)), userId,
                request.fingerprint());
    }

    private BigDecimal positive(BigDecimal value) {
        try {
            return checkoutMoney.normalizeNumericAmount(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private BigDecimal normalizeCashDenomination(com.cardovia.merkon.backend.entity.OrderPaymentMethod method,
                                                 BigDecimal denomination,
                                                 BigDecimal amount) {
        if (method != com.cardovia.merkon.backend.entity.OrderPaymentMethod.CASH) {
            if (denomination != null) throw invalid();
            return null;
        }
        if (denomination == null) throw invalid();
        BigDecimal normalized = positive(denomination);
        if (normalized.compareTo(amount) < 0) throw invalid();
        return normalized;
    }

    private String normalizeDescription(String value) {
        if (value == null) throw invalid();
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 500) throw invalid();
        return normalized;
    }

    private static OpenSaleException invalid() {
        return new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID);
    }
}
