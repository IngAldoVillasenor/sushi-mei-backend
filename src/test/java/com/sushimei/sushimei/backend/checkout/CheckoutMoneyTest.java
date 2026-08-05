package com.sushimei.sushimei.backend.checkout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutMoneyTest {

    private final CheckoutMoney checkoutMoney = new CheckoutMoney();

    @Test
    void normalizesIntegerPriceToTwoDecimals() {
        assertThat(checkoutMoney.normalizeLegacyUnitPrice(10.0d))
                .isEqualByComparingTo("10.00")
                .hasScaleOf(2);
    }

    @Test
    void normalizesOneDecimalPriceToTwoDecimals() {
        assertThat(checkoutMoney.normalizeLegacyUnitPrice(10.5d))
                .isEqualByComparingTo("10.50")
                .hasScaleOf(2);
    }

    @Test
    void preservesAnExactTwoDecimalPrice() {
        assertThat(checkoutMoney.normalizeLegacyUnitPrice(10.25d))
                .isEqualByComparingTo("10.25")
                .hasScaleOf(2);
    }

    @Test
    void rejectsNullPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(null));
    }

    @Test
    void rejectsNaNPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(Double.NaN));
    }

    @Test
    void rejectsPositiveInfinityPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNegativeInfinityPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(Double.NEGATIVE_INFINITY));
    }

    @Test
    void rejectsZeroAndNegativePrices() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(0.0d));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(-10.0d));
    }

    @Test
    void rejectsPriceWithMoreThanTwoMeaningfulFractionalDigits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(10.001d));
    }

    @Test
    void rejectsPriceThatExceedsPrecisionAfterNormalization() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.normalizeLegacyUnitPrice(Double.parseDouble("100000000000000000")));
    }

    @Test
    void acceptsPositiveQuantity() {
        assertThat(checkoutMoney.requirePositiveQuantity(3)).isEqualTo(3);
    }

    @Test
    void rejectsZeroAndNegativeQuantity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.requirePositiveQuantity(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkoutMoney.requirePositiveQuantity(-1));
    }

    @Test
    void calculatesExactLineTotalUsingBigDecimal() {
        assertThat(checkoutMoney.calculateLineTotal(3, new BigDecimal("10.50")))
                .isEqualByComparingTo("31.50")
                .hasScaleOf(2);
    }

    @Test
    void rejectsLineTotalThatExceedsPrecision() {
        assertThatThrownBy(() -> checkoutMoney.calculateLineTotal(
                Integer.MAX_VALUE, new BigDecimal("9999999999.99")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void calculatesExactCartTotalUsingBigDecimal() {
        assertThat(checkoutMoney.calculateCartTotal(List.of(
                new BigDecimal("10.50"), new BigDecimal("0.25"))))
                .isEqualByComparingTo("10.75")
                .hasScaleOf(2);
    }

    @Test
    void rejectsCartTotalThatExceedsPrecision() {
        assertThatThrownBy(() -> checkoutMoney.calculateCartTotal(List.of(
                new BigDecimal("99999999999999999.99"),
                new BigDecimal("99999999999999999.99"))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void performsArithmeticOnlyAfterLegacyValuesAreConverted() {
        BigDecimal first = checkoutMoney.normalizeLegacyUnitPrice(Double.valueOf("0.1"));
        BigDecimal second = checkoutMoney.normalizeLegacyUnitPrice(Double.valueOf("0.2"));

        assertThat(checkoutMoney.calculateCartTotal(List.of(first, second)))
                .isEqualByComparingTo("0.30")
                .hasScaleOf(2);
    }
}
