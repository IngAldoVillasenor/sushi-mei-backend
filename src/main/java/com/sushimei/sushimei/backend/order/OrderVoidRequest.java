package com.sushimei.sushimei.backend.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit operational evidence for voiding a physical POS order. */
public record OrderVoidRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
