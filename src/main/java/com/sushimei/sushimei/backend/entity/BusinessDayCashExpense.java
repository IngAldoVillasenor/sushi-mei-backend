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
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable evidence of physical cash leaving an open business-day drawer. */
@Entity
@Immutable
@Table(name = "business_day_cash_expenses")
public class BusinessDayCashExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_day_id", nullable = false)
    private Long businessDayId;

    @Column(name = "client_request_id", nullable = false, unique = true)
    private UUID clientRequestId;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    protected BusinessDayCashExpense() {
    }

    public static BusinessDayCashExpense create(Long businessDayId,
                                                UUID clientRequestId,
                                                String requestFingerprint,
                                                BigDecimal amount,
                                                String description,
                                                String note,
                                                Instant createdAt,
                                                Long createdByUserId) {
        BusinessDayCashExpense expense = new BusinessDayCashExpense();
        expense.businessDayId = Objects.requireNonNull(businessDayId, "businessDayId must not be null");
        expense.clientRequestId = Objects.requireNonNull(clientRequestId, "clientRequestId must not be null");
        expense.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        expense.amount = Objects.requireNonNull(amount, "amount must not be null");
        expense.description = Objects.requireNonNull(description, "description must not be null");
        expense.note = note;
        expense.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expense.createdByUserId = Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        return expense;
    }

    public Long getId() { return id; }
    public Long getBusinessDayId() { return businessDayId; }
    public UUID getClientRequestId() { return clientRequestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
}
