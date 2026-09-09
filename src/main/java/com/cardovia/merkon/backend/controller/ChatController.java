package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.conversation.ConversationManager;
import com.cardovia.merkon.backend.security.AuthenticatedLegacyBusinessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/sushi")
@ConditionalOnProperty(prefix = "merkon.features", name = {"ai.enabled", "whatsapp.enabled"}, havingValue = "true", matchIfMissing = true)
public class ChatController {

    private final ConversationManager conversationManager;
    private final AuthenticatedLegacyBusinessGuard authenticatedLegacyBusinessGuard;

    public ChatController(ConversationManager conversationManager,
                          AuthenticatedLegacyBusinessGuard authenticatedLegacyBusinessGuard) {
        this.conversationManager = conversationManager;
        this.authenticatedLegacyBusinessGuard = authenticatedLegacyBusinessGuard;
    }

    @GetMapping("/chat")
    public String chat(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String telefono,
            @RequestParam String mensaje) {
        authenticatedLegacyBusinessGuard.requireLegacyBusiness(jwt);
        return conversationManager.handleTextMessage(telefono, mensaje);
    }
}
