package com.sushimei.sushimei.backend.businessday;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CashExpenseRequest(
        @NotNull UUID requestId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2)
        BigDecimal amount,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 500) String note) {
}
