package com.sushimei.sushimei.backend.pos;

/** Immutable omitted default-component evidence accepted with a manual order. */
public record ManualOrderComponentOmissionResponse(
        Long id,
        Long sourceComponentId,
        String code,
        String displayName,
        String detail,
        int displayOrder) {
}
