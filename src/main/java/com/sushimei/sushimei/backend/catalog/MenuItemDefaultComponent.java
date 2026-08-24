package com.sushimei.sushimei.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * A server-managed component included with a menu item by default. Components
 * are not saleable menu items; they only describe no-charge customizations.
 */
@Entity
@Table(name = "menu_item_default_components")
public class MenuItemDefaultComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(name = "component_code", nullable = false, length = 80)
    private String componentCode;

    @Column(nullable = false, length = 160)
    private String displayName;

    @Column(name = "component_detail", length = 160)
    private String detail;

    @Column(name = "included_by_default", nullable = false)
    private boolean includedByDefault;

    @Column(nullable = false)
    private boolean removable;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    protected MenuItemDefaultComponent() {
        // JPA
    }

    public static MenuItemDefaultComponent create(MenuItem menuItem,
                                                   String componentCode,
                                                   String displayName,
                                                   String detail,
                                                   boolean includedByDefault,
                                                   boolean removable,
                                                   int displayOrder) {
        MenuItemDefaultComponent component = new MenuItemDefaultComponent();
        component.menuItem = Objects.requireNonNull(menuItem, "menuItem must not be null");
        component.componentCode = required(componentCode, 80, "componentCode");
        component.displayName = required(displayName, 160, "displayName");
        component.detail = optional(detail, 160, "detail");
        component.includedByDefault = includedByDefault;
        component.removable = removable;
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must not be negative");
        }
        component.displayOrder = displayOrder;
        component.active = true;
        return component;
    }

    public Long getId() { return id; }
    public MenuItem getMenuItem() { return menuItem; }
    public String getComponentCode() { return componentCode; }
    public String getDisplayName() { return displayName; }
    public String getDetail() { return detail; }
    public boolean isIncludedByDefault() { return includedByDefault; }
    public boolean isRemovable() { return removable; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isActive() { return active; }

    private static String required(String value, int maximum, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is outside the supported length");
        }
        return normalized;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is outside the supported length");
        }
        return normalized;
    }
}
