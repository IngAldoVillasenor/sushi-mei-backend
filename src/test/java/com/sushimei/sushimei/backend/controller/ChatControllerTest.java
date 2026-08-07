package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.conversation.ConversationManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void delegatesToTheGuardedConversationTextPath() {
        ConversationManager conversationManager = mock(ConversationManager.class);
        when(conversationManager.handleTextMessage("5214770000001", "Hola")).thenReturn("¡Hola!");
        ChatController controller = new ChatController(conversationManager);

        assertThat(controller.chat("5214770000001", "Hola")).isEqualTo("¡Hola!");

        verify(conversationManager).handleTextMessage("5214770000001", "Hola");
    }
}
