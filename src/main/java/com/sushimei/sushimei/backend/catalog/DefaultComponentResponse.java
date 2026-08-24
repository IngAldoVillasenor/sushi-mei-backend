package com.sushimei.sushimei.backend.catalog;

public record DefaultComponentResponse(
        Long id,
        String code,
        String displayName,
        String detail,
        boolean includedByDefault,
        boolean removable,
        int displayOrder,
        boolean active) {

    static DefaultComponentResponse from(MenuItemDefaultComponent component) {
        return new DefaultComponentResponse(
                component.getId(),
                component.getComponentCode(),
                component.getDisplayName(),
                component.getDetail(),
                component.isIncludedByDefault(),
                component.isRemovable(),
                component.getDisplayOrder(),
                component.isActive());
    }
}
