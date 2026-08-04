package com.sushimei.sushimei.backend.conversation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public class InvalidConversationTransitionException extends RuntimeException {

    private final ConversationState currentState;
    private final ConversationTransitionAction attemptedAction;
    private final Set<ConversationState> allowedSourceStates;

    public InvalidConversationTransitionException(ConversationState currentState,
                                                  ConversationTransitionAction attemptedAction,
                                                  Set<ConversationState> allowedSourceStates) {
        super("Conversation action " + Objects.requireNonNull(attemptedAction, "attemptedAction must not be null")
                + " is not valid from state " + Objects.requireNonNull(currentState, "currentState must not be null"));
        this.currentState = currentState;
        this.attemptedAction = attemptedAction;
        this.allowedSourceStates = Collections.unmodifiableSet(EnumSet.copyOf(
                Objects.requireNonNull(allowedSourceStates, "allowedSourceStates must not be null")));
    }

    public ConversationState getCurrentState() {
        return currentState;
    }

    public ConversationTransitionAction getAttemptedAction() {
        return attemptedAction;
    }

    public Set<ConversationState> getAllowedSourceStates() {
        return allowedSourceStates;
    }
}
