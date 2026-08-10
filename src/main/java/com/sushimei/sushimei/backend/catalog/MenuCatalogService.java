package com.sushimei.sushimei.backend.catalog;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    public List<MenuItemResponse> list(boolean includeInactive) {
        List<MenuItem> items = includeInactive
                ? menuCatalogRepository.findAllByOrderByCategoryAscDisplayOrderAscNameAscIdAsc()
                : menuCatalogRepository.findByActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
        return items.stream().map(MenuItemResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MenuItemResponse get(Long id) {
        return MenuItemResponse.from(findRequired(id));
    }

    @Transactional
    public MenuItemResponse create(CreateMenuItemRequest request) {
        if (request == null) {
            throw new MenuCatalogValidationException();
        }
        Instant now = clock.instant();
        MenuItem item = MenuItem.create(
                normalizeRequiredText(request.name(), MAX_NAME_LENGTH),
                normalizeOptionalText(request.description(), MAX_DESCRIPTION_LENGTH),
                normalizeRequiredText(request.category(), MAX_CATEGORY_LENGTH),
                normalizePrice(request.price()),
                request.available() == null || request.available(),
                normalizeDisplayOrder(request.displayOrder(), 0),
                now);
        return MenuItemResponse.from(menuCatalogRepository.saveAndFlush(item));
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
        Instant now = clock.instant();
        item.update(
                normalizeRequiredText(request.name(), MAX_NAME_LENGTH),
                normalizeOptionalText(request.description(), MAX_DESCRIPTION_LENGTH),
                normalizeRequiredText(request.category(), MAX_CATEGORY_LENGTH),
                normalizePrice(request.price()),
                requireBoolean(request.active()),
                requireBoolean(request.available()),
                normalizeDisplayOrder(request.displayOrder(), null),
                now);
        menuCatalogRepository.flush();
        return MenuItemResponse.from(item);
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

    private BigDecimal normalizePrice(BigDecimal price) {
        try {
            return checkoutMoney.normalizeNumericAmount(price);
        } catch (IllegalArgumentException exception) {
            throw new MenuCatalogValidationException();
        }
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
