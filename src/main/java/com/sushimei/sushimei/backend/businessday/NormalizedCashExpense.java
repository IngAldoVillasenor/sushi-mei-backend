package com.sushimei.sushimei.backend.businessday;

import java.math.BigDecimal;
import java.util.UUID;

record NormalizedCashExpense(UUID requestId,
                             BigDecimal amount,
                             String description,
                             String note,
                             String fingerprint) {
}
