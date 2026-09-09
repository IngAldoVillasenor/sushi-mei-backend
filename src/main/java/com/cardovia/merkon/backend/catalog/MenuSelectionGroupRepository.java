package com.cardovia.merkon.backend.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuSelectionGroupRepository extends JpaRepository<MenuSelectionGroup, Long> {

    List<MenuSelectionGroup> findByParentMenuItemIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long parentMenuItemId);

    List<MenuSelectionGroup> findByParentMenuItemIdOrderByDisplayOrderAscIdAsc(Long parentMenuItemId);

    List<MenuSelectionGroup> findByParentMenuItemIdAndNameOrderByIdAsc(Long parentMenuItemId, String name);

    Optional<MenuSelectionGroup> findByIdAndParentMenuItemId(Long id, Long parentMenuItemId);

    @Query("select selectionGroup from MenuSelectionGroup selectionGroup "
            + "where selectionGroup.id = :id and selectionGroup.parentMenuItem.business.id = :businessId")
    Optional<MenuSelectionGroup> findByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);
}
