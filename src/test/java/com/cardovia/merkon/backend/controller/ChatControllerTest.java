package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.conversation.ConversationManager;
import com.cardovia.merkon.backend.security.AuthenticatedLegacyBusinessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void delegatesToTheGuardedConversationTextPath() {
        ConversationManager conversationManager = mock(ConversationManager.class);
        AuthenticatedLegacyBusinessGuard legacyBusinessGuard = mock(AuthenticatedLegacyBusinessGuard.class);
        Jwt jwt = mock(Jwt.class);
        when(conversationManager.handleTextMessage("5214770000001", "Hola")).thenReturn("¡Hola!");
        ChatController controller = new ChatController(conversationManager, legacyBusinessGuard);

        assertThat(controller.chat(jwt, "5214770000001", "Hola")).isEqualTo("¡Hola!");

        var order = inOrder(legacyBusinessGuard, conversationManager);
        order.verify(legacyBusinessGuard).requireLegacyBusiness(jwt);
        order.verify(conversationManager).handleTextMessage("5214770000001", "Hola");
    }
}
