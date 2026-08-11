package com.sushimei.sushimei.backend.entity;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable server-resolved configuration evidence for one persisted order line. */
@Entity
@Table(name = "order_line_selection_snapshots")
public class OrderLineSelectionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false, updatable = false)
    private OrderLineRecord orderLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_selection_snapshot_id", updatable = false)
    private OrderLineSelectionSnapshot parentSelection;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "group_name", nullable = false, length = 160, updatable = false)
    private String groupName;

    @Column(name = "selection_position", nullable = false, updatable = false)
    private int selectionPosition;

    @Column(name = "selected_menu_item_id", nullable = false, updatable = false)
    private Long selectedMenuItemId;

    @Column(name = "selected_item_name", nullable = false, length = 160, updatable = false)
    private String selectedItemName;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "catalog_unit_price", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal catalogUnitPrice;

    @Column(name = "price_adjustment_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal priceAdjustmentAmount;

    protected OrderLineSelectionSnapshot() {
    }

    public static OrderLineSelectionSnapshot create(OrderLineSelectionSnapshot parentSelection,
                                                    Long groupId,
                                                    String groupName,
                                                    int selectionPosition,
                                                    Long selectedMenuItemId,
                                                    String selectedItemName,
                                                    int quantity,
                                                    BigDecimal catalogUnitPrice,
                                                    BigDecimal priceAdjustmentAmount) {
        OrderLineSelectionSnapshot snapshot = new OrderLineSelectionSnapshot();
        snapshot.parentSelection = parentSelection;
        snapshot.groupId = positiveId(groupId, "groupId");
        snapshot.groupName = nonBlank(groupName, "groupName", 160);
        snapshot.selectionPosition = positive(selectionPosition, "selectionPosition");
        snapshot.selectedMenuItemId = positiveId(selectedMenuItemId, "selectedMenuItemId");
        snapshot.selectedItemName = nonBlank(selectedItemName, "selectedItemName", 160);
        snapshot.quantity = positive(quantity, "quantity");
        snapshot.catalogUnitPrice = positiveMoney(catalogUnitPrice, "catalogUnitPrice");
        snapshot.priceAdjustmentAmount = nonNegativeMoney(priceAdjustmentAmount, "priceAdjustmentAmount");
        return snapshot;
    }

    void attachTo(OrderLineRecord orderLine) {
        this.orderLine = Objects.requireNonNull(orderLine, "orderLine must not be null");
    }

    public Long getId() { return id; }
    public Long getOrderLineId() { return orderLine == null ? null : orderLine.getId(); }
    public OrderLineSelectionSnapshot getParentSelection() { return parentSelection; }
    public Long getGroupId() { return groupId; }
    public String getGroupName() { return groupName; }
    public int getSelectionPosition() { return selectionPosition; }
    public Long getSelectedMenuItemId() { return selectedMenuItemId; }
    public String getSelectedItemName() { return selectedItemName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getCatalogUnitPrice() { return catalogUnitPrice; }
    public BigDecimal getPriceAdjustmentAmount() { return priceAdjustmentAmount; }

    private static Long positiveId(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String nonBlank(String value, String name, int length) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > length) {
            throw new IllegalArgumentException(name + " must be nonblank and bounded");
        }
        return value.trim();
    }

    private static BigDecimal positiveMoney(BigDecimal value, String name) {
        BigDecimal normalized = nonNegativeMoney(value, name);
        if (normalized.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return normalized;
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > CheckoutMoney.SCALE) {
            throw new IllegalArgumentException(name + " must be exact and non-negative");
        }
        BigDecimal normalized = value.setScale(CheckoutMoney.SCALE, RoundingMode.UNNECESSARY);
        if (normalized.precision() > CheckoutMoney.PRECISION) throw new IllegalArgumentException(name + " exceeds precision");
        return normalized;
    }
}
