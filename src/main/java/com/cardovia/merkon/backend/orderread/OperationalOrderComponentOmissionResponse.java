package com.cardovia.merkon.backend.orderread;

/** Immutable omitted default-component evidence for operational order reads. */
public record OperationalOrderComponentOmissionResponse(
        Long id,
        Long sourceComponentId,
        String code,
        String displayName,
        String detail,
        int displayOrder) {
}
