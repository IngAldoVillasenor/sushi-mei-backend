package com.sushimei.sushimei.backend.vendis;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** NDJSON source shape for one exported Vendis sale. Unknown export fields are deliberately ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VendisSaleInput(
        @JsonAlias({"transactionId", "id"}) String vendisTransactionId,
        @JsonAlias({"invoice", "invoiceNo"}) String invoiceNumber,
        @JsonAlias({"transactionDate", "date"}) String transactionDateRaw,
        @JsonAlias("paymentStatus") String detailPaymentStatus,
        String customerName,
        BigDecimal computedLineSubtotal,
        BigDecimal computedPaymentsTotal,
        BigDecimal reconciliationDifference,
        @JsonAlias({"items", "products"}) List<VendisLineInput> lines,
        @JsonAlias({"paymentRows", "paymentRecords"}) List<VendisPaymentInput> payments,
        VendisSaleSummaryInput summary
) {
    public VendisSaleInput {
        lines = lines == null ? List.of() : List.copyOf(lines);
        payments = payments == null ? List.of() : List.copyOf(payments);
    }

    String effectiveInvoiceNumber() {
        return invoiceNumber != null ? invoiceNumber : summary == null ? null : summary.invoiceNumber();
    }

    String effectiveTransactionDateRaw() {
        return transactionDateRaw != null ? transactionDateRaw : summary == null ? null : summary.transactionDateRaw();
    }
}
