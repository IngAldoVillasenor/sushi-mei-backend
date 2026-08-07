package com.sushimei.sushimei.backend.development;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Development-only request for exercising the production text conversation path without WhatsApp.
 */
public record LocalAiChatRequest(
        @NotBlank @Size(max = 128) String memoryId,
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 2_000) String message) {
}
