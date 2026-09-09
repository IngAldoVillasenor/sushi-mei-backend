package com.cardovia.merkon.backend.catalog;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative lookup and validation boundary for no-charge component omissions. */
@Service
public class MenuItemComponentService {

    private final MenuItemDefaultComponentRepository componentRepository;
    private final MenuCatalogRepository menuCatalogRepository;
    private final LegacyBusinessResolver legacyBusiness;

    public MenuItemComponentService(MenuItemDefaultComponentRepository componentRepository,
                                    MenuCatalogRepository menuCatalogRepository,
                                    LegacyBusinessResolver legacyBusiness) {
        this.componentRepository = Objects.requireNonNull(componentRepository, "componentRepository must not be null");
        this.menuCatalogRepository = Objects.requireNonNull(menuCatalogRepository, "menuCatalogRepository must not be null");
        this.legacyBusiness = Objects.requireNonNull(legacyBusiness, "legacyBusiness must not be null");
    }

    @Transactional(readOnly = true)
    public List<DefaultComponentResponse> activeComponents(Long menuItemId) {
        return activeComponents(legacyBusiness.requireLegacyBusinessId(), menuItemId);
    }

    @Transactional(readOnly = true)
    public List<DefaultComponentResponse> activeComponents(Long businessId, Long menuItemId) {
        requireMenuItemId(menuItemId);
        requireOwnedMenuItem(businessId, menuItemId);
        return componentRepository.findByMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(menuItemId).stream()
                .map(DefaultComponentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MenuItemDefaultComponent> resolveActiveOmittedComponents(Long menuItemId,
                                                                           Collection<Long> componentIds) {
        return resolveActiveOmittedComponents(legacyBusiness.requireLegacyBusinessId(), menuItemId, componentIds);
    }

    @Transactional(readOnly = true)
    public List<MenuItemDefaultComponent> resolveActiveOmittedComponents(Long businessId, Long menuItemId,
                                                                           Collection<Long> componentIds) {
        requireMenuItemId(menuItemId);
        requireOwnedMenuItem(businessId, menuItemId);
        if (componentIds == null || componentIds.isEmpty()) {
            return List.of();
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long componentId : componentIds) {
            if (componentId == null || componentId <= 0 || !uniqueIds.add(componentId)) {
                throw invalid();
            }
        }
        List<MenuItemDefaultComponent> components = componentRepository
                .findByMenuItemIdAndIdInAndActiveTrue(menuItemId, uniqueIds);
        if (components.size() != uniqueIds.size() || components.stream().anyMatch(component -> !component.isRemovable())) {
            throw invalid();
        }
        return components.stream()
                .sorted(Comparator.comparingInt(MenuItemDefaultComponent::getDisplayOrder)
                        .thenComparing(MenuItemDefaultComponent::getId))
                .toList();
    }

    private static void requireMenuItemId(Long menuItemId) {
        if (menuItemId == null || menuItemId <= 0) {
            throw invalid();
        }
    }

    private void requireOwnedMenuItem(Long businessId, Long menuItemId) {
        if (businessId == null || businessId <= 0
                || menuCatalogRepository.findByIdAndBusinessId(menuItemId, businessId).isEmpty()) {
            throw invalid();
        }
    }

    private static CatalogConfigurationException invalid() {
        return new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INVALID);
    }
}
