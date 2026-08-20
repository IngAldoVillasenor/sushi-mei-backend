package com.sushimei.sushimei.backend.vendis;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Validated, database-neutral Vendis evidence ready for one short persistence transaction. */
record MappedVendisSale(
        String transactionId,
        String invoiceNumber,
        LocalDateTime createdAtUtc,
        int isRevocate,
        String detailPaymentStatus,
        String summaryPaymentStatusRaw,
        String vendisStatus,
        String customerName,
        BigDecimal totalBeforeTax,
        BigDecimal sourceFinalTotal,
        BigDecimal projectedFinalTotal,
        BigDecimal discountAmount,
        String discountType,
        String contactId,
        String contactName,
        String businessLocationName,
        BigDecimal totalPaid,
        BigDecimal totalDebt,
        BigDecimal computedLineSubtotal,
        BigDecimal computedPaymentsTotal,
        BigDecimal saleReconciliationDifference,
        BigDecimal paymentReconciliationDifference,
        OrderPaymentMethod genericPaymentMethod,
        List<MappedVendisLine> lines,
        List<MappedVendisPayment> payments
) {
    boolean voided() {
        return isRevocate != 0;
    }

    boolean zeroTotal() {
        return sourceFinalTotal.signum() == 0;
    }

    boolean reconciliationMismatch() {
        return !voided() && paymentReconciliationDifference.signum() != 0;
    }

    long historicalProjectionAdjustments() {
        return lines.stream().filter(MappedVendisLine::hasProjectionAdjustment).count();
    }
}

record MappedVendisLine(
        int position,
        String name,
        String externalProductReference,
        String externalProductDetail,
        int quantity,
        BigDecimal sourceUnitPrice,
        BigDecimal projectedUnitPrice,
        BigDecimal discountAmount,
        BigDecimal discountPercentage,
        BigDecimal taxAmount,
        BigDecimal priceIncludingTaxAmount,
        BigDecimal sourceLineTotal,
        BigDecimal projectedLineTotal
) {
    boolean hasDiscount() {
        return (discountAmount != null && discountAmount.signum() > 0)
                || (discountPercentage != null && discountPercentage.signum() > 0);
    }

    boolean hasProjectionAdjustment() {
        return sourceLineTotal.compareTo(projectedLineTotal) != 0;
    }
}

record MappedVendisPayment(
        int position,
        String dateRaw,
        String reference,
        BigDecimal amount,
        String method,
        String note
) {
}
