package com.sushimei.sushimei.backend.entity;

import com.sushimei.sushimei.backend.businessday.BusinessDayStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "business_days")
public class BusinessDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false, unique = true)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BusinessDayStatus status;

    @Column(name = "opening_cash_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingCashAmount;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "opened_by_user_id", nullable = false)
    private Long openedByUserId;

    @Column(name = "completed_sales_amount", precision = 19, scale = 2)
    private BigDecimal completedSalesAmount;

    @Column(name = "cash_sales_amount", precision = 19, scale = 2)
    private BigDecimal cashSalesAmount;

    @Column(name = "cash_expense_amount", precision = 19, scale = 2)
    private BigDecimal cashExpenseAmount;

    @Column(name = "cash_expense_count")
    private Long cashExpenseCount;

    @Column(name = "transfer_sales_amount", precision = 19, scale = 2)
    private BigDecimal transferSalesAmount;

    @Column(name = "card_sales_amount", precision = 19, scale = 2)
    private BigDecimal cardSalesAmount;

    @Column(name = "unclassified_sales_amount", precision = 19, scale = 2)
    private BigDecimal unclassifiedSalesAmount;

    @Column(name = "completed_order_count")
    private Long completedOrderCount;

    @Column(name = "voided_order_count")
    private Long voidedOrderCount;

    @Column(name = "expected_closing_cash_amount", precision = 19, scale = 2)
    private BigDecimal expectedClosingCashAmount;

    @Column(name = "actual_closing_cash_amount", precision = 19, scale = 2)
    private BigDecimal actualClosingCashAmount;

    @Column(name = "cash_difference_amount", precision = 19, scale = 2)
    private BigDecimal cashDifferenceAmount;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "open_guard")
    private Integer openGuard;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopened_by_user_id")
    private Long reopenedByUserId;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Version
    @Column(nullable = false)
    private long version;

    protected BusinessDay() {
    }

    public static BusinessDay open(LocalDate businessDate,
                                   BigDecimal openingCashAmount,
                                   Instant openedAt,
                                   Long openedByUserId) {
        BusinessDay businessDay = new BusinessDay();
        businessDay.businessDate = Objects.requireNonNull(businessDate, "businessDate must not be null");
        businessDay.openingCashAmount = Objects.requireNonNull(openingCashAmount, "openingCashAmount must not be null");
        businessDay.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        businessDay.openedByUserId = Objects.requireNonNull(openedByUserId, "openedByUserId must not be null");
        businessDay.status = BusinessDayStatus.OPEN;
        businessDay.openGuard = 1;
        return businessDay;
    }

    public void close(BigDecimal completedSalesAmount,
                      BigDecimal cashSalesAmount,
                      BigDecimal cashExpenseAmount,
                      long cashExpenseCount,
                      BigDecimal transferSalesAmount,
                      BigDecimal cardSalesAmount,
                      BigDecimal unclassifiedSalesAmount,
                      long completedOrderCount,
                      long voidedOrderCount,
                      BigDecimal expectedClosingCashAmount,
                      BigDecimal actualClosingCashAmount,
                      BigDecimal cashDifferenceAmount,
                      Instant closedAt,
                      Long closedByUserId) {
        if (status != BusinessDayStatus.OPEN) {
            throw new IllegalStateException("Business day is not open");
        }
        this.completedSalesAmount = Objects.requireNonNull(completedSalesAmount, "completedSalesAmount must not be null");
        this.cashSalesAmount = Objects.requireNonNull(cashSalesAmount, "cashSalesAmount must not be null");
        this.cashExpenseAmount = Objects.requireNonNull(cashExpenseAmount, "cashExpenseAmount must not be null");
        this.cashExpenseCount = cashExpenseCount;
        this.transferSalesAmount = Objects.requireNonNull(transferSalesAmount, "transferSalesAmount must not be null");
        this.cardSalesAmount = Objects.requireNonNull(cardSalesAmount, "cardSalesAmount must not be null");
        this.unclassifiedSalesAmount = Objects.requireNonNull(unclassifiedSalesAmount, "unclassifiedSalesAmount must not be null");
        this.completedOrderCount = completedOrderCount;
        this.voidedOrderCount = voidedOrderCount;
        this.expectedClosingCashAmount = Objects.requireNonNull(expectedClosingCashAmount,
                "expectedClosingCashAmount must not be null");
        this.actualClosingCashAmount = Objects.requireNonNull(actualClosingCashAmount,
                "actualClosingCashAmount must not be null");
        this.cashDifferenceAmount = Objects.requireNonNull(cashDifferenceAmount,
                "cashDifferenceAmount must not be null");
        this.closedAt = Objects.requireNonNull(closedAt, "closedAt must not be null");
        this.closedByUserId = Objects.requireNonNull(closedByUserId, "closedByUserId must not be null");
        this.status = BusinessDayStatus.CLOSED;
        this.openGuard = null;
    }

    public void reopen(Instant reopenedAt, Long reopenedByUserId) {
        if (status != BusinessDayStatus.CLOSED) {
            throw new IllegalStateException("Business day is not closed");
        }
        this.completedSalesAmount = null;
        this.cashSalesAmount = null;
        this.cashExpenseAmount = null;
        this.cashExpenseCount = null;
        this.transferSalesAmount = null;
        this.cardSalesAmount = null;
        this.unclassifiedSalesAmount = null;
        this.completedOrderCount = null;
        this.voidedOrderCount = null;
        this.expectedClosingCashAmount = null;
        this.actualClosingCashAmount = null;
        this.cashDifferenceAmount = null;
        this.closedAt = null;
        this.closedByUserId = null;
        this.reopenedAt = Objects.requireNonNull(reopenedAt, "reopenedAt must not be null");
        this.reopenedByUserId = Objects.requireNonNull(reopenedByUserId, "reopenedByUserId must not be null");
        this.reopenCount++;
        this.status = BusinessDayStatus.OPEN;
        this.openGuard = 1;
    }

    public Long getId() { return id; }
    public LocalDate getBusinessDate() { return businessDate; }
    public BusinessDayStatus getStatus() { return status; }
    public BigDecimal getOpeningCashAmount() { return openingCashAmount; }
    public Instant getOpenedAt() { return openedAt; }
    public Long getOpenedByUserId() { return openedByUserId; }
    public BigDecimal getCompletedSalesAmount() { return completedSalesAmount; }
    public BigDecimal getCashSalesAmount() { return cashSalesAmount; }
    public BigDecimal getCashExpenseAmount() { return cashExpenseAmount; }
    public Long getCashExpenseCount() { return cashExpenseCount; }
    public BigDecimal getTransferSalesAmount() { return transferSalesAmount; }
    public BigDecimal getCardSalesAmount() { return cardSalesAmount; }
    public BigDecimal getUnclassifiedSalesAmount() { return unclassifiedSalesAmount; }
    public Long getCompletedOrderCount() { return completedOrderCount; }
    public Long getVoidedOrderCount() { return voidedOrderCount; }
    public BigDecimal getExpectedClosingCashAmount() { return expectedClosingCashAmount; }
    public BigDecimal getActualClosingCashAmount() { return actualClosingCashAmount; }
    public BigDecimal getCashDifferenceAmount() { return cashDifferenceAmount; }
    public Instant getClosedAt() { return closedAt; }
    public Long getClosedByUserId() { return closedByUserId; }
    public Instant getReopenedAt() { return reopenedAt; }
    public Long getReopenedByUserId() { return reopenedByUserId; }
    public int getReopenCount() { return reopenCount; }
    public long getVersion() { return version; }
}
