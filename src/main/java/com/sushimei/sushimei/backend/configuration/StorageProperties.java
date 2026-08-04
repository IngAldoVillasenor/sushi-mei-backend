package com.sushimei.sushimei.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        Path receiptsDirectory,
        Path publicUploadDirectory) {
}
