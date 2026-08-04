package com.sushimei.sushimei.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "whatsapp")
public record WhatsAppProperties(
        String apiToken,
        String phoneNumberId,
        String verifyToken,
        String graphApiVersion) {
}
