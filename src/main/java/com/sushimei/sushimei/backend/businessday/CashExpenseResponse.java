package com.sushimei.sushimei.backend.businessday;

import com.sushimei.sushimei.backend.entity.BusinessDayCashExpense;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashExpenseResponse(
        Long id,
        Long businessDayId,
        UUID requestId,
        BigDecimal amount,
        String description,
        String note,
        Instant createdAt,
        Long createdByUserId) {

    static CashExpenseResponse from(BusinessDayCashExpense expense) {
        return new CashExpenseResponse(expense.getId(), expense.getBusinessDayId(), expense.getClientRequestId(),
                expense.getAmount(), expense.getDescription(), expense.getNote(), expense.getCreatedAt(),
                expense.getCreatedByUserId());
    }
}
