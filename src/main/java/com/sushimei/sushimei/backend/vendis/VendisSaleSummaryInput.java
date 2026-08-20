package com.sushimei.sushimei.backend.vendis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VendisSaleSummaryInput(
        String status,
        String invoiceNumber,
        String transactionDateRaw,
        String paymentStatusRaw,
        BigDecimal totalBeforeTax,
        BigDecimal finalTotal,
        BigDecimal discountAmount,
        String discountType,
        Integer isRevocate,
        String contactId,
        String contactName,
        String businessLocationName,
        BigDecimal totalPaid,
        BigDecimal totalDebt
) {
}
