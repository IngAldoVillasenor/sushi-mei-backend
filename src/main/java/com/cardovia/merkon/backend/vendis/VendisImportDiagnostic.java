package com.cardovia.merkon.backend.vendis;

/** Safe source-location diagnostics; no full customer payload is retained or logged. */
public record VendisImportDiagnostic(long lineNumber, String vendisTransactionId, String reason) {
}
