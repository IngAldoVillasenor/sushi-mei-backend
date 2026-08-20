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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable source payment evidence. It intentionally permits payment sums that do not reconcile. */
@Entity
@Table(name = "vendis_payment_snapshots")
public class VendisPaymentSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderRecord order;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(name = "payment_date_raw", length = 80, updatable = false)
    private String paymentDateRaw;

    @Column(name = "payment_reference", length = 255, updatable = false)
    private String paymentReference;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "payment_method_raw", length = 120, updatable = false)
    private String paymentMethodRaw;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String note;

    protected VendisPaymentSnapshot() {
        // JPA
    }

    public static VendisPaymentSnapshot create(OrderRecord order,
                                               int position,
                                               String paymentDateRaw,
                                               String paymentReference,
                                               BigDecimal amount,
                                               String paymentMethodRaw,
                                               String note) {
        VendisPaymentSnapshot snapshot = new VendisPaymentSnapshot();
        snapshot.order = Objects.requireNonNull(order, "order must not be null");
        if (position <= 0) {
            throw new IllegalArgumentException("position must be positive");
        }
        snapshot.position = position;
        snapshot.paymentDateRaw = bounded(paymentDateRaw, 80, "paymentDateRaw");
        snapshot.paymentReference = bounded(paymentReference, 255, "paymentReference");
        snapshot.amount = nonNegativeAmount(amount);
        snapshot.paymentMethodRaw = bounded(paymentMethodRaw, 120, "paymentMethodRaw");
        snapshot.note = bounded(note, 10_000, "note");
        return snapshot;
    }

    public Long getId() { return id; }
    public int getPosition() { return position; }
    public String getPaymentDateRaw() { return paymentDateRaw; }
    public String getPaymentReference() { return paymentReference; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentMethodRaw() { return paymentMethodRaw; }
    public String getNote() { return note; }

    private static BigDecimal nonNegativeAmount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("amount must be a non-negative source amount with at most four decimals");
        }
        BigDecimal normalized = value.setScale(4, RoundingMode.UNNECESSARY);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException("amount exceeds source precision");
        }
        return normalized;
    }

    private static String bounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds supported length");
        }
        return normalized;
    }
}
