package com.sushimei.sushimei.backend.conversation;

import java.util.Objects;

public class ConversationSessionNotFoundException extends RuntimeException {

    private final ConversationTransitionAction attemptedAction;

    public ConversationSessionNotFoundException(ConversationTransitionAction attemptedAction) {
        super("Conversation session was not found for action "
                + Objects.requireNonNull(attemptedAction, "attemptedAction must not be null"));
        this.attemptedAction = attemptedAction;
    }

    public ConversationTransitionAction getAttemptedAction() {
        return attemptedAction;
    }
}
