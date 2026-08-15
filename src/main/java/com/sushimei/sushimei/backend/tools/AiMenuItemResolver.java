package com.sushimei.sushimei.backend.tools;

import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import com.sushimei.sushimei.backend.catalog.MenuItemPricingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Resolves AI tool input against the operational catalog without accepting AI-provided prices. */
@Service
public class AiMenuItemResolver {

    private static final Logger log = LoggerFactory.getLogger(AiMenuItemResolver.class);

    private final MenuCatalogRepository menuCatalogRepository;

    public AiMenuItemResolver(MenuCatalogRepository menuCatalogRepository) {
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository,
                "menuCatalogRepository must not be null");
    }

    @Transactional(readOnly = true)
    public ResolvedMenuItem resolveExact(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new AiMenuItemResolutionException();
        }
        List<MenuItem> matches = menuCatalogRepository
                .findByNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        requestedName.trim());
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
