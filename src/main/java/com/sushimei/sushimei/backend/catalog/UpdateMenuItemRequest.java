package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateMenuItemRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 120) String category,
        @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal price,
        @NotNull Boolean active,
        @NotNull Boolean available,
        @NotNull Boolean standaloneOrderable,
        @NotNull @Min(0) Integer displayOrder,
        @NotNull @Min(0) Long version,
        MenuItemPricingMode pricingMode
) {
    public UpdateMenuItemRequest(String name,
                                 String description,
                                 String category,
                                 BigDecimal price,
                                 Boolean active,
                                 Boolean available,
                                 Boolean standaloneOrderable,
                                 Integer displayOrder,
                                 Long version) {
        this(name, description, category, price, active, available, standaloneOrderable, displayOrder, version, null);
    }
}
