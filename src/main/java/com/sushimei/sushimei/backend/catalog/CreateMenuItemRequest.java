package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateMenuItemRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 120) String category,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal price,
        Boolean available,
        Boolean standaloneOrderable,
        Integer displayOrder
) {
}
