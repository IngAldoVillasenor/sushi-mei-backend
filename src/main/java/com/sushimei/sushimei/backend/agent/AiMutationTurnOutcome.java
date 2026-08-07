package com.sushimei.sushimei.backend.agent;

/**
 * Per-turn operational tool outcomes. Failed and blocked outcomes override any earlier successful outcome.
 */
public enum AiMutationTurnOutcome {
    NONE,
    ADD_SUCCEEDED,
    REMOVE_SUCCEEDED,
    CART_QUERY_SUCCEEDED,
    ADD_BLOCKED,
    REMOVE_BLOCKED,
    ADD_FAILED,
    REMOVE_FAILED,
    CONFIRMATION_BLOCKED;

    public boolean isSuccessfulCartOperation() {
        return this == ADD_SUCCEEDED || this == REMOVE_SUCCEEDED || this == CART_QUERY_SUCCEEDED;
    }
}