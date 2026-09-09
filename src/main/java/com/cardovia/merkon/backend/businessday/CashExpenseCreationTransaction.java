package com.cardovia.merkon.backend.businessday;

import com.cardovia.merkon.backend.entity.BusinessDay;
import com.cardovia.merkon.backend.entity.BusinessDayCashExpense;
import com.cardovia.merkon.backend.repository.BusinessDayCashExpenseRepository;
import com.cardovia.merkon.backend.repository.BusinessDayOperationLockRepository;
import com.cardovia.merkon.backend.repository.BusinessDayRepository;
import com.cardovia.merkon.backend.security.AppUserRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short transaction sharing the singleton business-day operation lock with close/reopen. */
@Service
class CashExpenseCreationTransaction {

    private final BusinessDayCashExpenseRepository cashExpenseRepository;
    private final BusinessDayRepository businessDayRepository;
    private final BusinessDayOperationLockRepository businessDayOperationLockRepository;
    private final AppUserRepository appUserRepository;
    private final BusinessRepository businessRepository;

    CashExpenseCreationTransaction(BusinessDayCashExpenseRepository cashExpenseRepository,
                                   BusinessDayRepository businessDayRepository,
                                   BusinessDayOperationLockRepository businessDayOperationLockRepository,
                                   AppUserRepository appUserRepository,
                                   BusinessRepository businessRepository) {
        this.cashExpenseRepository = Objects.requireNonNull(cashExpenseRepository, "cashExpenseRepository must not be null");
        this.businessDayRepository = Objects.requireNonNull(businessDayRepository, "businessDayRepository must not be null");
        this.businessDayOperationLockRepository = Objects.requireNonNull(businessDayOperationLockRepository,
                "businessDayOperationLockRepository must not be null");
        this.appUserRepository = Objects.requireNonNull(appUserRepository, "appUserRepository must not be null");
        this.businessRepository = Objects.requireNonNull(businessRepository, "businessRepository must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    CashExpenseCreateResponse create(Long businessId, Long userId, NormalizedCashExpense request, Instant createdAt) {
        businessDayOperationLockRepository.findByBusinessIdForUpdate(businessId)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));

        BusinessDayCashExpense existing = cashExpenseRepository.findByBusinessIdAndClientRequestId(businessId, request.requestId()).orElse(null);
        if (existing != null) {
            return existing(existing, userId, request.fingerprint());
        }

        appUserRepository.findById(userId).orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));
        BusinessDay businessDay = businessDayRepository.findOpenForUpdate(businessId)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED));
        BusinessDayCashExpense saved = cashExpenseRepository.saveAndFlush(BusinessDayCashExpense.create(
                businessRepository.getReferenceById(businessId), businessDay.getId(), request.requestId(), request.fingerprint(), request.amount(), request.description(),
                request.note(), createdAt, userId));
        return new CashExpenseCreateResponse(CashExpenseResponse.from(saved), CashExpenseResult.CREATED);
    }

    static CashExpenseCreateResponse existing(BusinessDayCashExpense expense, Long userId, String fingerprint) {
        if (!Objects.equals(expense.getCreatedByUserId(), userId)
                || !Objects.equals(expense.getRequestFingerprint(), fingerprint)) {
            throw failure(BusinessDayError.BUSINESS_DAY_CASH_EXPENSE_IDEMPOTENCY_CONFLICT);
        }
        return new CashExpenseCreateResponse(CashExpenseResponse.from(expense), CashExpenseResult.ALREADY_CREATED);
    }

    private static BusinessDayException failure(BusinessDayError error) {
        return new BusinessDayException(error);
    }
}
