package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Explicit price-bearing contract reserved exclusively for a non-catalog counter sale. */
public record OpenSaleRequest(
        @NotNull UUID requestId,
        @NotBlank @Size(max = 500) String description,
        @NotNull BigDecimal amount,
        @NotNull OrderPaymentMethod paymentMethod,
        BigDecimal cashDenomination) {
}
