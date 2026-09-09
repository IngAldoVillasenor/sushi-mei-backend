package com.cardovia.merkon.backend.tools;

import com.cardovia.merkon.backend.catalog.MenuCatalogRepository;
import com.cardovia.merkon.backend.catalog.MenuItem;
import com.cardovia.merkon.backend.catalog.MenuItemPricingMode;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Resolves AI tool input against the operational catalog without accepting AI-provided prices. */
@Service
@ConditionalOnProperty(prefix = "merkon.features.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiMenuItemResolver {

    private static final Logger log = LoggerFactory.getLogger(AiMenuItemResolver.class);

    private final MenuCatalogRepository menuCatalogRepository;
    private final LegacyBusinessResolver legacyBusinessResolver;

    public AiMenuItemResolver(MenuCatalogRepository menuCatalogRepository,
                              LegacyBusinessResolver legacyBusinessResolver) {
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository,
                "menuCatalogRepository must not be null");
        this.legacyBusinessResolver = Objects.requireNonNull(legacyBusinessResolver,
                "legacyBusinessResolver must not be null");
    }

    @Transactional(readOnly = true)
    public ResolvedMenuItem resolveExact(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new AiMenuItemResolutionException();
        }
        List<MenuItem> matches = menuCatalogRepository
                .findByBusinessIdAndNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        legacyBusinessResolver.requireLegacyBusinessId(), requestedName.trim());
        if (matches.size() != 1) {
            log.warn("AI menu resolution outcome=REJECTED reason=NO_UNIQUE_ORDERABLE_MATCH");
            throw new AiMenuItemResolutionException();
        }
        MenuItem item = matches.get(0);
        if (item.getPricingMode() != MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS
                || item.getPriceAmount().signum() <= 0) {
            log.warn("AI menu resolution outcome=REJECTED reason=UNSUPPORTED_PRICING_MODE menuItemId={}",
                    item.getId());
            throw new AiMenuItemResolutionException();
        }
        log.info("AI menu resolution outcome=RESOLVED menuItemId={}", item.getId());
        return new ResolvedMenuItem(item.getName(), item.getPriceAmount());
    }
}

final class AiMenuItemResolutionException extends RuntimeException {

    AiMenuItemResolutionException() {
        super("AI menu item could not be resolved against the operational catalog.");
    }
}
