package com.sushimei.sushimei.backend.vendis;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VendisPaymentInput(
        Integer position,
        @JsonAlias("paymentDate") String date,
        String reference,
        BigDecimal amount,
        String method,
        String note
) {
}
