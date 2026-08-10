package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCatalogTagRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull Boolean active,
        @NotNull @Min(0) Integer displayOrder,
        @NotNull @Min(0) Long version
) {
}
