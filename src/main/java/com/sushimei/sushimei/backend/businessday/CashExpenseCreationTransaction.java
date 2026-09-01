package com.sushimei.sushimei.backend.businessday;

import com.sushimei.sushimei.backend.entity.BusinessDay;
import com.sushimei.sushimei.backend.entity.BusinessDayCashExpense;
import com.sushimei.sushimei.backend.repository.BusinessDayCashExpenseRepository;
import com.sushimei.sushimei.backend.repository.BusinessDayOperationLockRepository;
import com.sushimei.sushimei.backend.repository.BusinessDayRepository;
import com.sushimei.sushimei.backend.security.AppUserRepository;
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

    CashExpenseCreationTransaction(BusinessDayCashExpenseRepository cashExpenseRepository,
                                   BusinessDayRepository businessDayRepository,
                                   BusinessDayOperationLockRepository businessDayOperationLockRepository,
                                   AppUserRepository appUserRepository) {
        this.cashExpenseRepository = Objects.requireNonNull(cashExpenseRepository, "cashExpenseRepository must not be null");
        this.businessDayRepository = Objects.requireNonNull(businessDayRepository, "businessDayRepository must not be null");
        this.businessDayOperationLockRepository = Objects.requireNonNull(businessDayOperationLockRepository,
                "businessDayOperationLockRepository must not be null");
        this.appUserRepository = Objects.requireNonNull(appUserRepository, "appUserRepository must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    CashExpenseCreateResponse create(Long userId, NormalizedCashExpense request, Instant createdAt) {
        businessDayOperationLockRepository.findSingletonForUpdate()
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));

        BusinessDayCashExpense existing = cashExpenseRepository.findByClientRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return existing(existing, userId, request.fingerprint());
        }

        appUserRepository.findById(userId).orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));
        BusinessDay businessDay = businessDayRepository.findOpenForUpdate()
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED));
        BusinessDayCashExpense saved = cashExpenseRepository.saveAndFlush(BusinessDayCashExpense.create(
                businessDay.getId(), request.requestId(), request.fingerprint(), request.amount(), request.description(),
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
