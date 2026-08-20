package com.sushimei.sushimei.backend.vendis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VendisLineInput(
        Integer position,
        String name,
        String externalProductReference,
        String externalProductDetail,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal discount,
        BigDecimal discountPercentage,
        BigDecimal tax,
        BigDecimal priceIncludingTax,
        BigDecimal lineTotal
) {
}
