package com.sushimei.sushimei.backend.checkout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelMoneyResolverTest {

    private final ParallelMoneyResolver resolver = new ParallelMoneyResolver(new CheckoutMoney());

    @Test
    void resolvesNumericOnlyData() {
        assertThat(resolver.resolve(new BigDecimal("10.50"), null))
                .isEqualByComparingTo("10.50")
                .hasScaleOf(2);
    }

    @Test
    void resolvesLegacyOnlyData() {
        assertThat(resolver.resolve(null, 10.5d))
                .isEqualByComparingTo("10.50")
                .hasScaleOf(2);
    }

    @Test
    void resolvesMatchingDualRepresentations() {
        assertThat(resolver.resolve(new BigDecimal("10.50"), 10.5d))
                .isEqualByComparingTo("10.50");
    }

    @Test
    void rejectsMissingRepresentations() {
        assertReason(MonetaryCompatibilityReason.BOTH_REPRESENTATIONS_ABSENT,
                () -> resolver.resolve(null, null));
    }

    @Test
    void rejectsInvalidNumericRepresentation() {
        assertReason(MonetaryCompatibilityReason.INVALID_NUMERIC_REPRESENTATION,
                () -> resolver.resolve(new BigDecimal("10.001"), null));
    }

    @Test
    void rejectsNonFiniteLegacyRepresentations() {
        assertReason(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION,
                () -> resolver.resolve(null, Double.NaN));
        assertReason(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION,
                () -> resolver.resolve(null, Double.POSITIVE_INFINITY));
        assertReason(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION,
                () -> resolver.resolve(null, Double.NEGATIVE_INFINITY));
    }

    @Test
    void rejectsMismatchingDualRepresentations() {
        assertReason(MonetaryCompatibilityReason.REPRESENTATIONS_DISAGREE,
                () -> resolver.resolve(new BigDecimal("10.50"), 10.51d));
    }

    @Test
    void preparesValidatedPairFromLegacyValue() {
        ParallelMoney money = resolver.forWriteFromLegacy(10.5d);

        assertThat(money.numericAmount()).isEqualByComparingTo("10.50");
        assertThat(money.legacyAmount()).isEqualTo(10.5d);
    }

    @Test
    void preparesValidatedPairFromExactNumericValue() {
        ParallelMoney money = resolver.forWriteFromExact(new BigDecimal("10.50"));

        assertThat(money.numericAmount()).isEqualByComparingTo("10.50");
        assertThat(money.legacyAmount()).isEqualTo(10.5d);
        assertThat(resolver.resolve(money.numericAmount(), money.legacyAmount()))
                .isEqualByComparingTo("10.50");
    }

    @Test
    void acceptsJavaCanonicalLegacyValueAtTheSupportedPrecisionBoundary() {
        double legacyAmount = 99999999999999.98d;
        BigDecimal numericAmount = new BigDecimal("99999999999999.98");

        assertThat(resolver.forWriteFromLegacy(legacyAmount).numericAmount())
                .isEqualByComparingTo(numericAmount);
        assertThat(resolver.resolve(numericAmount, legacyAmount))
                .isEqualByComparingTo(numericAmount);
    }

    @Test
    void rejectsExactNumericAmountThatCannotRoundTripThroughDouble() {
        double legacyAmount = 99999999999999.98d;
        BigDecimal exactAmount = new BigDecimal("99999999999999.99");

        assertReason(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION,
                () -> resolver.forWriteFromExact(exactAmount));
        assertReason(MonetaryCompatibilityReason.REPRESENTATIONS_DISAGREE,
                () -> resolver.resolve(exactAmount, legacyAmount));
    }

    private void assertReason(MonetaryCompatibilityReason expected,
                              org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(MonetaryCompatibilityException.class)
                .extracting(exception -> ((MonetaryCompatibilityException) exception).getReason())
                .isEqualTo(expected);
    }
}
