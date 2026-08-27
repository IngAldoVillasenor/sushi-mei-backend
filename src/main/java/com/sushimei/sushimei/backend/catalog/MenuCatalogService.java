package com.sushimei.sushimei.backend.catalog;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Objects;

@Service
public class MenuCatalogService {

    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_CATEGORY_LENGTH = 120;

    private final MenuCatalogRepository menuCatalogRepository;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;

    public MenuCatalogService(MenuCatalogRepository menuCatalogRepository,
                              CheckoutMoney checkoutMoney,
                              Clock clock) {
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository,
                "menuCatalogRepository must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(boolean includeInactive, boolean standaloneOnly) {
        List<MenuItem> items;
        if (standaloneOnly) {
            items = menuCatalogRepository.findByActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
        } else if (includeInactive) {
            items = menuCatalogRepository.findAllByOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
        } else {
            items = menuCatalogRepository.findByActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
        }
        Set<Long> configuredItemIds = items.isEmpty()
                ? Set.of()
                : Set.copyOf(menuCatalogRepository.findIdsWithRequiredSelectionGroups(
                        items.stream().map(MenuItem::getId).toList()));
        return items.stream().map(item -> MenuItemResponse.from(item, configuredItemIds.contains(item.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public MenuItemResponse get(Long id) {
        MenuItem item = findRequired(id);
        return MenuItemResponse.from(item,
                !menuCatalogRepository.findIdsWithRequiredSelectionGroups(List.of(item.getId())).isEmpty());
    }

    @Transactional
    public MenuItemResponse create(CreateMenuItemRequest request) {
        if (request == null) {
            throw new MenuCatalogValidationException();
        }
        MenuItemPricingMode pricingMode = normalizePricingMode(request.pricingMode(),
                MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS);
        Instant now = clock.instant();
        MenuItem item = MenuItem.create(
                normalizeRequiredText(request.name(), MAX_NAME_LENGTH),
                normalizeOptionalText(request.description(), MAX_DESCRIPTION_LENGTH),
                normalizeRequiredText(request.category(), MAX_CATEGORY_LENGTH),
                normalizePrice(request.price(), pricingMode),
                pricingMode,
                request.available() == null || request.available(),
                request.standaloneOrderable() == null || request.standaloneOrderable(),
                normalizeDisplayOrder(request.displayOrder(), 0),
                now);
        return responseFor(menuCatalogRepository.saveAndFlush(item));
    }

    @Transactional
    public MenuItemResponse update(Long id, UpdateMenuItemRequest request) {
        if (request == null || request.version() == null) {
            throw new MenuCatalogValidationException();
        }
        MenuItem item = findRequired(id);
        if (item.getVersion() != request.version()) {
            throw new MenuCatalogVersionConflictException();
        }
        MenuItemPricingMode pricingMode = normalizePricingMode(request.pricingMode(), item.getPricingMode());
        Instant now = clock.instant();
        item.update(
                normalizeRequiredText(request.name(), MAX_NAME_LENGTH),
                normalizeOptionalText(request.description(), MAX_DESCRIPTION_LENGTH),
                normalizeRequiredText(request.category(), MAX_CATEGORY_LENGTH),
                normalizePrice(request.price(), pricingMode),
                pricingMode,
                requireBoolean(request.active()),
                requireBoolean(request.available()),
                requireBoolean(request.standaloneOrderable()),
                normalizeDisplayOrder(request.displayOrder(), null),
                now);
        menuCatalogRepository.flush();
        return responseFor(item);
    }

    @Transactional
    public void archive(Long id) {
        MenuItem item = findRequired(id);
        item.archive(clock.instant());
    }

    private MenuItem findRequired(Long id) {
        if (id == null || id <= 0) {
            throw new MenuCatalogItemNotFoundException();
        }
        return menuCatalogRepository.findById(id).orElseThrow(MenuCatalogItemNotFoundException::new);
    }

    private BigDecimal normalizePrice(BigDecimal price, MenuItemPricingMode pricingMode) {
        try {
            BigDecimal normalized = checkoutMoney.normalizeNonNegativeNumericAmount(price);
            if (pricingMode == MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS && normalized.signum() <= 0) {
                throw new IllegalArgumentException("base price must be positive");
            }
            if (pricingMode == MenuItemPricingMode.SELECTION_SUM && normalized.signum() != 0) {
                throw new IllegalArgumentException("selection-sum price must be zero");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new MenuCatalogValidationException();
        }
    }

    private MenuItemResponse responseFor(MenuItem item) {
        boolean requiresConfiguration = !menuCatalogRepository.findIdsWithRequiredSelectionGroups(List.of(item.getId())).isEmpty();
        return MenuItemResponse.from(item, requiresConfiguration);
    }

    private MenuItemPricingMode normalizePricingMode(MenuItemPricingMode requested,
                                                      MenuItemPricingMode defaultValue) {
        return requested == null ? defaultValue : requested;
    }

    private String normalizeRequiredText(String value, int maximumLength) {
        if (value == null) {
            throw new MenuCatalogValidationException();
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new MenuCatalogValidationException();
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new MenuCatalogValidationException();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean requireBoolean(Boolean value) {
        if (value == null) {
            throw new MenuCatalogValidationException();
        }
        return value;
    }

    private int normalizeDisplayOrder(Integer displayOrder, Integer defaultValue) {
        Integer value = displayOrder == null ? defaultValue : displayOrder;
        if (value == null || value < 0) {
            throw new MenuCatalogValidationException();
        }
        return value;
    }
}
