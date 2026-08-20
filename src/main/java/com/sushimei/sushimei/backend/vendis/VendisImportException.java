package com.sushimei.sushimei.backend.vendis;

/** Actual imports stop at the first malformed source record; prior per-sale commits are safe to rerun. */
public class VendisImportException extends RuntimeException {

    private final VendisImportDiagnostic diagnostic;

    public VendisImportException(VendisImportDiagnostic diagnostic, Throwable cause) {
        super("Vendis import failed at input line " + diagnostic.lineNumber()
                + (diagnostic.vendisTransactionId() == null ? "" : " for transaction " + diagnostic.vendisTransactionId())
                + ": " + diagnostic.reason(), cause);
        this.diagnostic = diagnostic;
    }

    public VendisImportDiagnostic getDiagnostic() {
        return diagnostic;
    }
}
