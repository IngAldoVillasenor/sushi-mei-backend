package com.sushimei.sushimei.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "menu_selection_groups")
public class MenuSelectionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_menu_item_id", nullable = false)
    private MenuItem parentMenuItem;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "min_selections", nullable = false)
    private int minSelections;

    @Column(name = "max_selections", nullable = false)
    private int maxSelections;

    @Column(name = "allow_duplicates", nullable = false)
    private boolean allowDuplicates;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MenuSelectionGroup() {
        // JPA
    }

    static MenuSelectionGroup create(MenuItem parentMenuItem,
                                     String name,
                                     int minSelections,
                                     int maxSelections,
                                     boolean allowDuplicates,
                                     int displayOrder,
                                     Instant now) {
        MenuSelectionGroup group = new MenuSelectionGroup();
        group.parentMenuItem = Objects.requireNonNull(parentMenuItem, "parentMenuItem must not be null");
        group.name = Objects.requireNonNull(name, "name must not be null");
        group.minSelections = minSelections;
        group.maxSelections = maxSelections;
        group.allowDuplicates = allowDuplicates;
        group.displayOrder = displayOrder;
        group.active = true;
        group.createdAt = Objects.requireNonNull(now, "now must not be null");
        group.updatedAt = now;
        return group;
    }

    void update(String name,
                int minSelections,
                int maxSelections,
                boolean allowDuplicates,
                int displayOrder,
                boolean active,
                Instant now) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
        this.allowDuplicates = allowDuplicates;
        this.displayOrder = displayOrder;
        this.active = active;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    void archive(Instant now) {
        this.active = false;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() {
        return id;
    }

    public MenuItem getParentMenuItem() {
        return parentMenuItem;
    }

    public String getName() {
        return name;
    }

    public int getMinSelections() {
        return minSelections;
    }

    public int getMaxSelections() {
        return maxSelections;
    }

    public boolean isAllowDuplicates() {
        return allowDuplicates;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
