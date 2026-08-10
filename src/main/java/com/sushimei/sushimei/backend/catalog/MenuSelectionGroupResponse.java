package com.sushimei.sushimei.backend.catalog;

import java.time.Instant;
import java.util.Objects;

public record MenuSelectionGroupResponse(
        Long id,
        Long parentMenuItemId,
        String name,
        int minSelections,
        int maxSelections,
        boolean allowDuplicates,
        int displayOrder,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public MenuSelectionGroupResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(parentMenuItemId, "parentMenuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static MenuSelectionGroupResponse from(MenuSelectionGroup group) {
        return new MenuSelectionGroupResponse(group.getId(), group.getParentMenuItem().getId(), group.getName(),
                group.getMinSelections(), group.getMaxSelections(), group.isAllowDuplicates(), group.getDisplayOrder(),
                group.isActive(), group.getVersion(), group.getCreatedAt(), group.getUpdatedAt());
    }
}
