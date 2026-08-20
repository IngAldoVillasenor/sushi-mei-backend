package com.sushimei.sushimei.backend.vendis;

import java.util.List;

/** Deterministic counters for one dry run or import execution. */
public record VendisImportReport(
        long inputSales,
        long validSales,
        long imported,
        long alreadyExisting,
        long voided,
        long completed,
        long lines,
        long payments,
        long zeroTotalSales,
        long zeroTotalLines,
        long missingExternalProductReferences,
        long globalDiscounts,
        long lineDiscounts,
        long historicalProjectionAdjustments,
        long paymentReconciliationMismatches,
        long errors,
        List<VendisImportDiagnostic> diagnostics
) {
    public VendisImportReport {
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }
}
