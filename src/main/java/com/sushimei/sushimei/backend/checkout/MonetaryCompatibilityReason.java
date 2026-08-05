package com.sushimei.sushimei.backend.checkout;

/**
 * Non-sensitive reasons why legacy floating-point and parallel numeric money
 * representations cannot safely coexist.
 */
public enum MonetaryCompatibilityReason {
    BOTH_REPRESENTATIONS_ABSENT,
    INVALID_NUMERIC_REPRESENTATION,
    INVALID_LEGACY_REPRESENTATION,
    REPRESENTATIONS_DISAGREE
}
