package com.sushimei.sushimei.backend.development;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-only manual harness. It reuses the production text conversation path and never sends a WhatsApp message.
 */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "development.ai-harness", name = "enabled", havingValue = "true")
@RequestMapping("/internal/dev/ai")
public class LocalAiConversationHarnessController {

    private final LocalAiConversationHarnessService harnessService;

    public LocalAiConversationHarnessController(LocalAiConversationHarnessService harnessService) {
        this.harnessService = harnessService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LocalAiChatResponse chat(@Valid @RequestBody LocalAiChatRequest request) {
        return new LocalAiChatResponse(harnessService.chat(request.memoryId(), request.phone(), request.message()));
    }
}
