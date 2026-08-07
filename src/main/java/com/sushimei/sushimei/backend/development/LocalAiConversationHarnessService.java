package com.sushimei.sushimei.backend.development;

import com.sushimei.sushimei.backend.conversation.ConversationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local")
@ConditionalOnProperty(prefix = "development.ai-harness", name = "enabled", havingValue = "true")
public class LocalAiConversationHarnessService {

    private static final Logger log = LoggerFactory.getLogger(LocalAiConversationHarnessService.class);

    private final ConversationManager conversationManager;

    public LocalAiConversationHarnessService(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    public String chat(String memoryId, String phone, String message) {
        String validatedMemoryId = requireNonBlank(memoryId, "memoryId");
        String validatedPhone = requireNonBlank(phone, "phone");
        String validatedMessage = requireNonBlank(message, "message");

        try {
            String response = conversationManager.handleTextMessage(validatedMemoryId, validatedPhone, validatedMessage);
            log.info("Local AI harness outcome=RESPONSE_GENERATED");
            return response;
        } catch (RuntimeException exception) {
            log.warn("Local AI harness outcome=FAILURE reason={}", exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
