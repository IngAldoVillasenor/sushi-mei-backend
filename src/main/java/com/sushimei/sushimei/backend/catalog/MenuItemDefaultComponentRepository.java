package com.sushimei.sushimei.backend.catalog;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemDefaultComponentRepository extends JpaRepository<MenuItemDefaultComponent, Long> {

    List<MenuItemDefaultComponent> findByMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long menuItemId);

    List<MenuItemDefaultComponent> findByMenuItemIdAndIdInAndActiveTrue(Long menuItemId, Collection<Long> ids);
}
