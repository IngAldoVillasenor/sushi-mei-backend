package com.sushimei.sushimei.backend.businessday;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.entity.BusinessDay;
import com.sushimei.sushimei.backend.entity.BusinessDayCashExpense;
import com.sushimei.sushimei.backend.repository.BusinessDayCashExpenseRepository;
import com.sushimei.sushimei.backend.repository.BusinessDayRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative append-only cash-drawer outgoing movement boundary. */
@Service
public class CashExpenseService {

    private final CashExpenseFingerprint fingerprint;
    private final CashExpenseCreationTransaction creationTransaction;
    private final BusinessDayCashExpenseRepository cashExpenseRepository;
    private final BusinessDayRepository businessDayRepository;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;
    private final ZoneId businessZone;

    public CashExpenseService(CashExpenseFingerprint fingerprint,
                              CashExpenseCreationTransaction creationTransaction,
                              BusinessDayCashExpenseRepository cashExpenseRepository,
                              BusinessDayRepository businessDayRepository,
                              CheckoutMoney checkoutMoney,
                              Clock clock,
                              @Value("${sushimei.business-zone:America/Mexico_City}") String businessZone) {
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        this.creationTransaction = Objects.requireNonNull(creationTransaction, "creationTransaction must not be null");
        this.cashExpenseRepository = Objects.requireNonNull(cashExpenseRepository,
                "cashExpenseRepository must not be null");
        this.businessDayRepository = Objects.requireNonNull(businessDayRepository, "businessDayRepository must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.businessZone = ZoneId.of(Objects.requireNonNull(businessZone, "businessZone must not be null"));
    }

    public CashExpenseCreateResponse create(Long authenticatedUserId, CashExpenseRequest request) {
        requireActor(authenticatedUserId);
        if (request == null || request.requestId() == null) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }
        BigDecimal amount = positive(request.amount());
        String description = requiredText(request.description());
        String note = optionalText(request.note());
        String requestFingerprint = fingerprint.fingerprint(amount, description, note);
        NormalizedCashExpense normalized = new NormalizedCashExpense(request.requestId(), amount, description, note,
                requestFingerprint);

        BusinessDayCashExpense existing = cashExpenseRepository.findByClientRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return CashExpenseCreationTransaction.existing(existing, authenticatedUserId, requestFingerprint);
        }
        try {
            return creationTransaction.create(authenticatedUserId, normalized, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            BusinessDayCashExpense raced = cashExpenseRepository.findByClientRequestId(request.requestId()).orElse(null);
            if (raced != null) {
                return CashExpenseCreationTransaction.existing(raced, authenticatedUserId, requestFingerprint);
            }
            throw new BusinessDayException(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    @Transactional(readOnly = true)
    public List<CashExpenseResponse> listCurrent() {
        BusinessDay businessDay = businessDayRepository.findByStatus(BusinessDayStatus.OPEN).orElseGet(() -> {
            LocalDate today = clock.instant().atZone(businessZone).toLocalDate();
            return businessDayRepository.findByBusinessDate(today).orElse(null);
        });
        if (businessDay == null) {
            return List.of();
        }
        return cashExpenseRepository.findByBusinessDayIdOrderByCreatedAtAscIdAsc(businessDay.getId()).stream()
                .map(CashExpenseResponse::from)
                .toList();
    }

    private BigDecimal positive(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private String requiredText(String value) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }
        return normalized;
    }

    private String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 500) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }
        return normalized;
    }

    private static void requireActor(Long userId) {
        if (userId == null || userId <= 0) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }
    }

    private static BusinessDayException failure(BusinessDayError error) {
        return new BusinessDayException(error);
    }

    private static BusinessDayException failure(BusinessDayError error, Throwable cause) {
        return new BusinessDayException(error, cause);
    }
}
