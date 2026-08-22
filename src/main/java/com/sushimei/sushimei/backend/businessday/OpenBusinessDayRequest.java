package com.sushimei.sushimei.backend.businessday;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record OpenBusinessDayRequest(
        @NotNull @DecimalMin(value = "0.00", inclusive = true) @Digits(integer = 17, fraction = 2)
        BigDecimal openingCashAmount) {
}
