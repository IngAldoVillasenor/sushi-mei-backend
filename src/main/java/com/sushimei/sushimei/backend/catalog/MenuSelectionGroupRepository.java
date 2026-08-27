package com.sushimei.sushimei.backend.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuSelectionGroupRepository extends JpaRepository<MenuSelectionGroup, Long> {

    List<MenuSelectionGroup> findByParentMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long parentMenuItemId);

    List<MenuSelectionGroup> findByParentMenuItemIdOrderByDisplayOrderAscIdAsc(Long parentMenuItemId);

    List<MenuSelectionGroup> findByParentMenuItemIdAndNameOrderByIdAsc(Long parentMenuItemId, String name);

    Optional<MenuSelectionGroup> findByIdAndParentMenuItemId(Long id, Long parentMenuItemId);
}
