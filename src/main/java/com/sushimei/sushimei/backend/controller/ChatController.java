package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.conversation.ConversationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@RequestMapping("/api/sushi")
@ConditionalOnProperty(prefix = "sushimei.features", name = {"ai.enabled", "whatsapp.enabled"}, havingValue = "true", matchIfMissing = true)
public class ChatController {

    private final ConversationManager conversationManager;

    public ChatController(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @GetMapping("/chat")
    public String chat(
            @RequestParam String telefono,
            @RequestParam String mensaje) {

        return conversationManager.handleTextMessage(telefono, mensaje);
    }
}
