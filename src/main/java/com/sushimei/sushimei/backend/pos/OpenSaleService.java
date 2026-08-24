package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.businessday.BusinessDayError;
import com.sushimei.sushimei.backend.businessday.BusinessDayException;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.repository.OrderRepository;
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

    public OpenSaleService(OpenSaleFingerprint fingerprint,
                           OpenSaleCreationTransaction creationTransaction,
                           OrderRepository orderRepository,
                           CheckoutMoney checkoutMoney,
                           Clock clock) {
        this.fingerprint = fingerprint;
        this.creationTransaction = creationTransaction;
        this.orderRepository = orderRepository;
        this.checkoutMoney = checkoutMoney;
        this.clock = clock;
    }

    public OpenSaleResponse create(Long authenticatedUserId, OpenSaleRequest request) {
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
        if (orderRepository.findByClientRequestId(request.requestId()).isPresent()) {
            return existing(normalized, authenticatedUserId);
        }
        try {
            return creationTransaction.create(authenticatedUserId, normalized, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            if (orderRepository.findByClientRequestId(request.requestId()).isPresent()) {
                return existing(normalized, authenticatedUserId);
            }
            throw new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID, exception);
        } catch (BusinessDayException exception) {
            if (exception.getError() == BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED) {
                throw new OpenSaleException(OpenSaleError.OPEN_SALE_BUSINESS_DAY_OPEN_REQUIRED, exception);
            }
            throw new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID, exception);
        }
    }

    private OpenSaleResponse existing(NormalizedOpenSale request, Long userId) {
        return OpenSaleCreationTransaction.existing(orderRepository.findByClientRequestIdWithOrderLines(request.requestId())
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

    private BigDecimal normalizeCashDenomination(com.sushimei.sushimei.backend.entity.OrderPaymentMethod method,
                                                 BigDecimal denomination,
                                                 BigDecimal amount) {
        if (method != com.sushimei.sushimei.backend.entity.OrderPaymentMethod.CASH) {
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
