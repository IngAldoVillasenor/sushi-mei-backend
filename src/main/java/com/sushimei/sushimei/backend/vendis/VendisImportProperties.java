package com.sushimei.sushimei.backend.vendis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicitly opt-in local-file import configuration; no remote Vendis access exists. */
@ConfigurationProperties(prefix = "sushimei.vendis-import")
public record VendisImportProperties(boolean enabled, String file, boolean dryRun) {

    public VendisImportProperties {
        if (enabled && (file == null || file.isBlank())) {
            throw new IllegalArgumentException("sushimei.vendis-import.file is required when the import is enabled");
        }
    }
}
