package com.sushimei.sushimei.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/** Immutable default-component omission evidence belonging to one selected-item occurrence. */
@Entity
@Table(name = "order_line_selection_component_omissions")
public class OrderLineSelectionComponentOmissionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "selection_snapshot_id", nullable = false, updatable = false)
    private OrderLineSelectionSnapshot selectionSnapshot;

    @Column(name = "source_component_id", nullable = false, updatable = false)
    private Long sourceComponentId;

    @Column(name = "component_code", nullable = false, length = 80, updatable = false)
    private String componentCode;

    @Column(name = "component_name", nullable = false, length = 160, updatable = false)
    private String componentName;

    @Column(name = "component_detail", length = 160, updatable = false)
    private String componentDetail;

    @Column(name = "component_display_order", nullable = false, updatable = false)
    private int componentDisplayOrder;

    protected OrderLineSelectionComponentOmissionSnapshot() { }

    public static OrderLineSelectionComponentOmissionSnapshot create(Long sourceComponentId,
                                                                       String componentCode,
                                                                       String componentName,
                                                                       String componentDetail,
                                                                       int componentDisplayOrder) {
        if (sourceComponentId == null || sourceComponentId <= 0 || componentDisplayOrder < 0) {
            throw new IllegalArgumentException("Selected-item component omission identity is invalid");
        }
        OrderLineSelectionComponentOmissionSnapshot snapshot = new OrderLineSelectionComponentOmissionSnapshot();
        snapshot.sourceComponentId = sourceComponentId;
        snapshot.componentCode = required(componentCode, 80);
        snapshot.componentName = required(componentName, 160);
        snapshot.componentDetail = optional(componentDetail, 160);
        snapshot.componentDisplayOrder = componentDisplayOrder;
        return snapshot;
    }

    void attachTo(OrderLineSelectionSnapshot snapshot) {
        this.selectionSnapshot = Objects.requireNonNull(snapshot, "selectionSnapshot must not be null");
    }

    public Long getId() { return id; }
    public Long getSelectionSnapshotId() { return selectionSnapshot == null ? null : selectionSnapshot.getId(); }
    public Long getSourceComponentId() { return sourceComponentId; }
    public String getComponentCode() { return componentCode; }
    public String getComponentName() { return componentName; }
    public String getComponentDetail() { return componentDetail; }
    public int getComponentDisplayOrder() { return componentDisplayOrder; }

    private static String required(String value, int maximum) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maximum) {
            throw new IllegalArgumentException("Selected-item component omission text is invalid");
        }
        return value.trim();
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.trim().isEmpty()) return null;
        if (value.trim().length() > maximum) throw new IllegalArgumentException("Selected-item component omission detail is invalid");
        return value.trim();
    }
}
