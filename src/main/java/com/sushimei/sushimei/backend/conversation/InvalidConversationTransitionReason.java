package com.sushimei.sushimei.backend.conversation;

public enum InvalidConversationTransitionReason {
    INVALID_SOURCE_STATE,
    INVALID_INPUT,
    INVARIANT_VIOLATION,
    UNSUPPORTED_OPTION
}
