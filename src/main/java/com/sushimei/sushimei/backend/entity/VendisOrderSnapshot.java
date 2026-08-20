package com.sushimei.sushimei.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/** Immutable source-level evidence from one retired Vendis transaction. */
@Entity
@Table(name = "vendis_order_snapshots")
public class VendisOrderSnapshot {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderRecord order;

    @Column(name = "detail_payment_status", length = 120, updatable = false)
    private String detailPaymentStatus;

    @Column(name = "summary_payment_status_raw", length = 120, updatable = false)
    private String summaryPaymentStatusRaw;

    @Column(name = "vendis_status", length = 120, updatable = false)
    private String vendisStatus;

    @Column(name = "customer_name", length = 255, updatable = false)
    private String customerName;

    @Column(name = "total_before_tax", precision = 19, scale = 4, updatable = false)
    private BigDecimal totalBeforeTax;

    @Column(name = "final_total_source", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal finalTotalSource;

    @Column(name = "discount_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "discount_type", length = 32, updatable = false)
    private String discountType;

    @Column(name = "is_revocate", nullable = false, updatable = false)
    private int isRevocate;

    @Column(name = "contact_id", length = 120, updatable = false)
    private String contactId;

    @Column(name = "contact_name", length = 255, updatable = false)
    private String contactName;

    @Column(name = "business_location_name", length = 255, updatable = false)
    private String businessLocationName;

    @Column(name = "total_paid", precision = 19, scale = 4, updatable = false)
    private BigDecimal totalPaid;

    @Column(name = "total_debt", precision = 19, scale = 4, updatable = false)
    private BigDecimal totalDebt;

    @Column(name = "computed_line_subtotal", precision = 19, scale = 4, updatable = false)
    private BigDecimal computedLineSubtotal;

    @Column(name = "computed_payments_total", precision = 19, scale = 4, updatable = false)
    private BigDecimal computedPaymentsTotal;

    @Column(name = "sale_reconciliation_difference", precision = 19, scale = 4, updatable = false)
    private BigDecimal saleReconciliationDifference;

    @Column(name = "payment_reconciliation_difference", precision = 19, scale = 4, updatable = false)
    private BigDecimal paymentReconciliationDifference;

    protected VendisOrderSnapshot() {
        // JPA
    }

    public static VendisOrderSnapshot create(OrderRecord order,
                                             String detailPaymentStatus,
                                             String summaryPaymentStatusRaw,
                                             String vendisStatus,
                                             String customerName,
                                             BigDecimal totalBeforeTax,
                                             BigDecimal finalTotalSource,
                                             BigDecimal discountAmount,
                                             String discountType,
                                             int isRevocate,
                                             String contactId,
                                             String contactName,
                                             String businessLocationName,
                                             BigDecimal totalPaid,
                                             BigDecimal totalDebt,
                                             BigDecimal computedLineSubtotal,
                                             BigDecimal computedPaymentsTotal,
                                             BigDecimal saleReconciliationDifference,
                                             BigDecimal paymentReconciliationDifference) {
        VendisOrderSnapshot snapshot = new VendisOrderSnapshot();
        snapshot.order = Objects.requireNonNull(order, "order must not be null");
        snapshot.detailPaymentStatus = bounded(detailPaymentStatus, 120, "detailPaymentStatus");
        snapshot.summaryPaymentStatusRaw = bounded(summaryPaymentStatusRaw, 120, "summaryPaymentStatusRaw");
        snapshot.vendisStatus = bounded(vendisStatus, 120, "vendisStatus");
        snapshot.customerName = bounded(customerName, 255, "customerName");
        snapshot.totalBeforeTax = sourceAmount(totalBeforeTax, "totalBeforeTax");
        snapshot.finalTotalSource = requiredSourceAmount(finalTotalSource, "finalTotalSource");
        snapshot.discountAmount = sourceAmount(discountAmount, "discountAmount");
        snapshot.discountType = bounded(discountType, 32, "discountType");
        snapshot.isRevocate = isRevocate;
        snapshot.contactId = bounded(contactId, 120, "contactId");
        snapshot.contactName = bounded(contactName, 255, "contactName");
        snapshot.businessLocationName = bounded(businessLocationName, 255, "businessLocationName");
        snapshot.totalPaid = sourceAmount(totalPaid, "totalPaid");
        snapshot.totalDebt = sourceAmount(totalDebt, "totalDebt");
        snapshot.computedLineSubtotal = sourceAmount(computedLineSubtotal, "computedLineSubtotal");
        snapshot.computedPaymentsTotal = sourceAmount(computedPaymentsTotal, "computedPaymentsTotal");
        snapshot.saleReconciliationDifference = signedSourceAmount(
                saleReconciliationDifference, "saleReconciliationDifference");
        snapshot.paymentReconciliationDifference = signedSourceAmount(
                paymentReconciliationDifference, "paymentReconciliationDifference");
        return snapshot;
    }

    public Long getOrderId() { return orderId; }
    public String getDetailPaymentStatus() { return detailPaymentStatus; }
    public String getSummaryPaymentStatusRaw() { return summaryPaymentStatusRaw; }
    public String getVendisStatus() { return vendisStatus; }
    public String getCustomerName() { return customerName; }
    public BigDecimal getTotalBeforeTax() { return totalBeforeTax; }
    public BigDecimal getFinalTotalSource() { return finalTotalSource; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getDiscountType() { return discountType; }
    public int getIsRevocate() { return isRevocate; }
    public String getContactId() { return contactId; }
    public String getContactName() { return contactName; }
    public String getBusinessLocationName() { return businessLocationName; }
    public BigDecimal getTotalPaid() { return totalPaid; }
    public BigDecimal getTotalDebt() { return totalDebt; }
    public BigDecimal getComputedLineSubtotal() { return computedLineSubtotal; }
    public BigDecimal getComputedPaymentsTotal() { return computedPaymentsTotal; }
    public BigDecimal getSaleReconciliationDifference() { return saleReconciliationDifference; }
    public BigDecimal getPaymentReconciliationDifference() { return paymentReconciliationDifference; }

    private static BigDecimal requiredSourceAmount(BigDecimal amount, String name) {
        BigDecimal normalized = sourceAmount(amount, name);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return normalized;
    }

    private static BigDecimal sourceAmount(BigDecimal amount, String name) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException(name + " must be a non-negative source amount with at most four decimals");
        }
        BigDecimal normalized = amount.setScale(4);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " exceeds source precision");
        }
        return normalized;
    }

    private static BigDecimal signedSourceAmount(BigDecimal amount, String name) {
        if (amount == null) {
            return null;
        }
        if (amount.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException(name + " has more than four meaningful decimals");
        }
        BigDecimal normalized = amount.setScale(4);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " exceeds source precision");
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
