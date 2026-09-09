package com.cardovia.merkon.backend.business;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit server-owned tenant binding for the retained Sushi Mei WhatsApp,
 * catalog bootstrap and legacy service paths. It never chooses a business by
 * position, count or display name.
 */
@Service
public class LegacyBusinessResolver {

    public static final String SUSHIMEI_LEGACY_KEY = "SUSHIMEI_LEGACY";

    private final BusinessRepository businesses;

    public LegacyBusinessResolver(BusinessRepository businesses) {
        this.businesses = Objects.requireNonNull(businesses, "businesses must not be null");
    }

    @Transactional(readOnly = true)
    public Business requireLegacyBusiness() {
        return businesses.findByLegacyKey(SUSHIMEI_LEGACY_KEY)
                .filter(Business::isActive)
                .orElseThrow(() -> new IllegalStateException("Legacy Sushi Mei business binding is unavailable"));
    }

    public Long requireLegacyBusinessId() {
        return requireLegacyBusiness().getId();
    }
}
