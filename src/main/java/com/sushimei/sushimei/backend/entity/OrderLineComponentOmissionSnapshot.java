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

/** Immutable evidence of a server-authorized default-component omission. */
@Entity
@Table(name = "order_line_component_omissions")
public class OrderLineComponentOmissionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false, updatable = false)
    private OrderLineRecord orderLine;

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

    protected OrderLineComponentOmissionSnapshot() {
        // JPA
    }

    public static OrderLineComponentOmissionSnapshot create(Long sourceComponentId,
                                                              String componentCode,
                                                              String componentName,
                                                              String componentDetail,
                                                              int componentDisplayOrder) {
        if (sourceComponentId == null || sourceComponentId <= 0 || componentDisplayOrder < 0) {
            throw new IllegalArgumentException("Component omission identity is invalid");
        }
        OrderLineComponentOmissionSnapshot snapshot = new OrderLineComponentOmissionSnapshot();
        snapshot.sourceComponentId = sourceComponentId;
        snapshot.componentCode = required(componentCode, 80);
        snapshot.componentName = required(componentName, 160);
        snapshot.componentDetail = optional(componentDetail, 160);
        snapshot.componentDisplayOrder = componentDisplayOrder;
        return snapshot;
    }

    void attachTo(OrderLineRecord line) {
        this.orderLine = Objects.requireNonNull(line, "line must not be null");
    }

    public Long getId() { return id; }
    public Long getOrderLineId() { return orderLine == null ? null : orderLine.getId(); }
    public Long getSourceComponentId() { return sourceComponentId; }
    public String getComponentCode() { return componentCode; }
    public String getComponentName() { return componentName; }
    public String getComponentDetail() { return componentDetail; }
    public int getComponentDisplayOrder() { return componentDisplayOrder; }

    private static String required(String value, int maximum) {
        if (value == null) {
            throw new IllegalArgumentException("Component omission text must not be blank");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException("Component omission text is invalid");
        }
        return normalized;
    }

    private static String optional(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("Component omission detail is invalid");
        }
        return normalized;
    }
}
