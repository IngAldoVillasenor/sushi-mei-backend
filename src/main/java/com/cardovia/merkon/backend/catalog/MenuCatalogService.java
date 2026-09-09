package com.cardovia.merkon.backend.catalog;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import com.cardovia.merkon.backend.checkout.CheckoutMoney;
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
    private final BusinessRepository businesses;
    private final LegacyBusinessResolver legacyBusiness;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;

    public MenuCatalogService(MenuCatalogRepository menuCatalogRepository,
                              BusinessRepository businesses,
                              LegacyBusinessResolver legacyBusiness,
                              CheckoutMoney checkoutMoney,
                              Clock clock) {
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository,
                "menuCatalogRepository must not be null");
        this.businesses = Objects.requireNonNull(businesses, "businesses must not be null");
        this.legacyBusiness = Objects.requireNonNull(legacyBusiness, "legacyBusiness must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(boolean includeInactive, boolean standaloneOnly) {
        return list(legacyBusiness.requireLegacyBusinessId(), includeInactive, standaloneOnly);
    }

    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(Long businessId, boolean includeInactive, boolean standaloneOnly) {
        List<MenuItem> items;
        if (standaloneOnly) {
            items = menuCatalogRepository.findByBusinessIdAndActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc(businessId);
        } else if (includeInactive) {
            items = menuCatalogRepository.findByBusinessIdOrderByCategoryAscDisplayOrderAscNameAscIdAsc(businessId);
        } else {
            items = menuCatalogRepository.findByBusinessIdAndActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc(businessId);
        }
        Set<Long> configuredItemIds = items.isEmpty()
                ? Set.of()
                : Set.copyOf(menuCatalogRepository.findIdsWithRequiredSelectionGroups(businessId,
                        items.stream().map(MenuItem::getId).toList()));
        return items.stream().map(item -> MenuItemResponse.from(item, configuredItemIds.contains(item.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public MenuItemResponse get(Long id) {
        return get(legacyBusiness.requireLegacyBusinessId(), id);
    }

    @Transactional(readOnly = true)
    public MenuItemResponse get(Long businessId, Long id) {
        MenuItem item = findRequired(businessId, id);
        return MenuItemResponse.from(item,
                !menuCatalogRepository.findIdsWithRequiredSelectionGroups(businessId, List.of(item.getId())).isEmpty());
    }

    @Transactional
    public MenuItemResponse create(CreateMenuItemRequest request) {
        return create(legacyBusiness.requireLegacyBusinessId(), request);
    }

    @Transactional
    public MenuItemResponse create(Long businessId, CreateMenuItemRequest request) {
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
        item.assignBusiness(requireBusiness(businessId));
        return responseFor(businessId, menuCatalogRepository.saveAndFlush(item));
    }

    @Transactional
    public MenuItemResponse update(Long id, UpdateMenuItemRequest request) {
        return update(legacyBusiness.requireLegacyBusinessId(), id, request);
    }

    @Transactional
    public MenuItemResponse update(Long businessId, Long id, UpdateMenuItemRequest request) {
        if (request == null || request.version() == null) {
            throw new MenuCatalogValidationException();
        }
        MenuItem item = findRequired(businessId, id);
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
        return responseFor(businessId, item);
    }

    @Transactional
    public void archive(Long id) {
        archive(legacyBusiness.requireLegacyBusinessId(), id);
    }

    @Transactional
    public void archive(Long businessId, Long id) {
        MenuItem item = findRequired(businessId, id);
        item.archive(clock.instant());
    }

    private MenuItem findRequired(Long businessId, Long id) {
        if (id == null || id <= 0) {
            throw new MenuCatalogItemNotFoundException();
        }
        return menuCatalogRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(MenuCatalogItemNotFoundException::new);
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

    private MenuItemResponse responseFor(Long businessId, MenuItem item) {
        boolean requiresConfiguration = !menuCatalogRepository.findIdsWithRequiredSelectionGroups(businessId, List.of(item.getId())).isEmpty();
        return MenuItemResponse.from(item, requiresConfiguration);
    }

    private Business requireBusiness(Long businessId) {
        if (businessId == null || businessId <= 0) {
            throw new MenuCatalogItemNotFoundException();
        }
        return businesses.findById(businessId).filter(Business::isActive)
                .orElseThrow(MenuCatalogItemNotFoundException::new);
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
