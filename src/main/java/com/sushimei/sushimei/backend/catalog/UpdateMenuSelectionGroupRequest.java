package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMenuSelectionGroupRequest(
        @NotBlank @Size(max = 160) String name,
        @NotNull @Min(0) Integer minSelections,
        @NotNull @Min(1) Integer maxSelections,
        @NotNull Boolean allowDuplicates,
        @NotNull @Min(0) Integer displayOrder,
        @NotNull Boolean active,
        @NotNull @Min(0) Long version
) {
}
