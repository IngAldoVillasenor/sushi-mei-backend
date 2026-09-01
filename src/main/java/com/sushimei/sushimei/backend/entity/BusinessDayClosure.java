package com.sushimei.sushimei.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/** Immutable evidence captured each time a business day successfully closes. */
@Entity
@Immutable
@Table(name = "business_day_closures")
public class BusinessDayClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_day_id", nullable = false)
    private Long businessDayId;

    @Column(name = "close_number", nullable = false)
    private int closeNumber;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "closed_by_user_id", nullable = false)
    private Long closedByUserId;

    @Column(name = "opening_cash_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingCashAmount;

    @Column(name = "completed_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal completedSalesAmount;

    @Column(name = "cash_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashSalesAmount;

    @Column(name = "cash_expense_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashExpenseAmount;

    @Column(name = "cash_expense_count", nullable = false)
    private long cashExpenseCount;

    @Column(name = "transfer_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal transferSalesAmount;

    @Column(name = "card_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cardSalesAmount;

    @Column(name = "unclassified_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal unclassifiedSalesAmount;

    @Column(name = "completed_order_count", nullable = false)
    private long completedOrderCount;

    @Column(name = "voided_order_count", nullable = false)
    private long voidedOrderCount;

    @Column(name = "expected_closing_cash_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedClosingCashAmount;

    @Column(name = "actual_closing_cash_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal actualClosingCashAmount;

    @Column(name = "cash_difference_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashDifferenceAmount;

    protected BusinessDayClosure() {
    }

    public static BusinessDayClosure from(BusinessDay businessDay, int closeNumber) {
        Objects.requireNonNull(businessDay, "businessDay must not be null");
        if (businessDay.getId() == null || businessDay.getClosedAt() == null || businessDay.getClosedByUserId() == null) {
            throw new IllegalArgumentException("Business day must have a persisted close snapshot");
        }
        BusinessDayClosure closure = new BusinessDayClosure();
        closure.businessDayId = businessDay.getId();
        closure.closeNumber = closeNumber;
        closure.closedAt = businessDay.getClosedAt();
        closure.closedByUserId = businessDay.getClosedByUserId();
        closure.openingCashAmount = businessDay.getOpeningCashAmount();
        closure.completedSalesAmount = businessDay.getCompletedSalesAmount();
        closure.cashSalesAmount = businessDay.getCashSalesAmount();
        closure.cashExpenseAmount = businessDay.getCashExpenseAmount();
        closure.cashExpenseCount = businessDay.getCashExpenseCount();
        closure.transferSalesAmount = businessDay.getTransferSalesAmount();
        closure.cardSalesAmount = businessDay.getCardSalesAmount();
        closure.unclassifiedSalesAmount = businessDay.getUnclassifiedSalesAmount();
        closure.completedOrderCount = businessDay.getCompletedOrderCount();
        closure.voidedOrderCount = businessDay.getVoidedOrderCount();
        closure.expectedClosingCashAmount = businessDay.getExpectedClosingCashAmount();
        closure.actualClosingCashAmount = businessDay.getActualClosingCashAmount();
        closure.cashDifferenceAmount = businessDay.getCashDifferenceAmount();
        return closure;
    }

    public Long getId() { return id; }
    public Long getBusinessDayId() { return businessDayId; }
    public int getCloseNumber() { return closeNumber; }
    public Instant getClosedAt() { return closedAt; }
    public Long getClosedByUserId() { return closedByUserId; }
    public BigDecimal getOpeningCashAmount() { return openingCashAmount; }
    public BigDecimal getCompletedSalesAmount() { return completedSalesAmount; }
    public BigDecimal getCashSalesAmount() { return cashSalesAmount; }
    public BigDecimal getCashExpenseAmount() { return cashExpenseAmount; }
    public long getCashExpenseCount() { return cashExpenseCount; }
    public BigDecimal getTransferSalesAmount() { return transferSalesAmount; }
    public BigDecimal getCardSalesAmount() { return cardSalesAmount; }
    public BigDecimal getUnclassifiedSalesAmount() { return unclassifiedSalesAmount; }
    public long getCompletedOrderCount() { return completedOrderCount; }
    public long getVoidedOrderCount() { return voidedOrderCount; }
    public BigDecimal getExpectedClosingCashAmount() { return expectedClosingCashAmount; }
    public BigDecimal getActualClosingCashAmount() { return actualClosingCashAmount; }
    public BigDecimal getCashDifferenceAmount() { return cashDifferenceAmount; }
}
