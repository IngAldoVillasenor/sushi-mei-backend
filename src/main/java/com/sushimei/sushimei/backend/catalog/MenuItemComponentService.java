package com.sushimei.sushimei.backend.catalog;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative lookup and validation boundary for no-charge component omissions. */
@Service
public class MenuItemComponentService {

    private final MenuItemDefaultComponentRepository componentRepository;

    public MenuItemComponentService(MenuItemDefaultComponentRepository componentRepository) {
        this.componentRepository = Objects.requireNonNull(componentRepository, "componentRepository must not be null");
    }

    @Transactional(readOnly = true)
    public List<DefaultComponentResponse> activeComponents(Long menuItemId) {
        requireMenuItemId(menuItemId);
        return componentRepository.findByMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(menuItemId).stream()
                .map(DefaultComponentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MenuItemDefaultComponent> resolveActiveOmittedComponents(Long menuItemId,
                                                                           Collection<Long> componentIds) {
        requireMenuItemId(menuItemId);
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

    private static CatalogConfigurationException invalid() {
        return new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INVALID);
    }
}
