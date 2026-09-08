package com.cardovia.merkon.backend.pos;

import java.math.BigDecimal;

/** Normalized, explicit manual-priced occurrence which is not eligible for catalog promotions. */
record NormalizedManualPricedLine(String lineKey,
                                  String description,
                                  int quantity,
                                  BigDecimal unitAmount,
                                  BigDecimal lineTotal) {
}
