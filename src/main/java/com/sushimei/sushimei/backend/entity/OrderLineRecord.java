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
import java.util.Objects;

/**
 * Immutable monetary snapshot of one cart item at deterministic order creation.
 * It deliberately has no foreign key to cart_items so historical order evidence
 * survives later cart lifecycle changes.
 */
@Entity
@Table(name = "order_lines")
public class OrderLineRecord {

    private static final int MAX_DISH_NAME_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderRecord order;

    @Column(name = "source_cart_item_id", nullable = false, updatable = false)
    private Long sourceCartItemId;

    @Column(name = "line_position", nullable = false, updatable = false)
    private int linePosition;

    @Column(name = "dish_name", nullable = false, length = MAX_DISH_NAME_LENGTH, updatable = false)
    private String dishName;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal unitPriceAmount;

    @Column(name = "line_total_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal lineTotalAmount;

    protected OrderLineRecord() {
        // JPA
    }

    public static OrderLineRecord create(Long sourceCartItemId,
                                         int linePosition,
                                         String dishName,
                                         int quantity,
                                         BigDecimal unitPriceAmount,
                                         BigDecimal lineTotalAmount) {
        return new OrderLineRecord(
                Objects.requireNonNull(sourceCartItemId, "sourceCartItemId must not be null"),
                requirePositive(linePosition, "linePosition"),
                normalizeDishName(dishName),
                requirePositive(quantity, "quantity"),
                requireNormalizedPositiveAmount(unitPriceAmount, "unitPriceAmount"),
                requireNormalizedPositiveAmount(lineTotalAmount, "lineTotalAmount"));
    }

    private OrderLineRecord(Long sourceCartItemId,
                            int linePosition,
                            String dishName,
                            int quantity,
                            BigDecimal unitPriceAmount,
                            BigDecimal lineTotalAmount) {
        BigDecimal expectedLineTotal = unitPriceAmount.multiply(BigDecimal.valueOf(quantity));
        if (!expectedLineTotal.equals(lineTotalAmount)) {
            throw new IllegalArgumentException("lineTotalAmount must equal quantity multiplied by unitPriceAmount");
        }
        this.sourceCartItemId = sourceCartItemId;
        this.linePosition = linePosition;
        this.dishName = dishName;
        this.quantity = quantity;
        this.unitPriceAmount = unitPriceAmount;
        this.lineTotalAmount = lineTotalAmount;
    }

    void attachTo(OrderRecord order) {
        this.order = Objects.requireNonNull(order, "order must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getSourceCartItemId() {
        return sourceCartItemId;
    }

    public int getLinePosition() {
        return linePosition;
    }

    public String getDishName() {
        return dishName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceAmount() {
        return unitPriceAmount;
    }

    public BigDecimal getLineTotalAmount() {
        return lineTotalAmount;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String normalizeDishName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("dishName must not be blank");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_DISH_NAME_LENGTH) {
            throw new IllegalArgumentException("dishName is outside the supported length");
        }
        return normalized;
    }

    private static BigDecimal requireNormalizedPositiveAmount(BigDecimal value, String fieldName) {
        if (value == null
                || value.signum() <= 0
                || value.scale() != CheckoutMoney.SCALE
                || value.precision() > CheckoutMoney.PRECISION) {
            throw new IllegalArgumentException(fieldName + " must be a normalized positive checkout amount");
        }
        return value;
    }
}
