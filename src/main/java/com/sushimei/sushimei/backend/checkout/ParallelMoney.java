package com.sushimei.sushimei.backend.checkout;

import java.math.BigDecimal;

/** A validated pair for the temporary dual-write monetary representation. */
public final class ParallelMoney {

    private final BigDecimal numericAmount;
    private final Double legacyAmount;

    ParallelMoney(BigDecimal numericAmount, Double legacyAmount) {
        this.numericAmount = numericAmount;
        this.legacyAmount = legacyAmount;
    }

    public BigDecimal numericAmount() {
        return numericAmount;
    }

    public Double legacyAmount() {
        return legacyAmount;
    }
}
