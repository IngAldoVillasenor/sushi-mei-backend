package com.sushimei.sushimei.backend.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "conversation_sessions")
public class ConversationSession {

    @Id
    @Column(name = "phone_number", nullable = false, updatable = false, length = 32)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private ConversationState state = ConversationState.ORDERING;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type", length = 16)
    private FulfillmentType fulfillmentType;

    @Column(name = "delivery_address", length = 500)
    private String deliveryAddress;

    @Column(name = "pickup_name", length = 120)
    private String pickupName;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 16)
    private PaymentMethod paymentMethod;

    @Column(name = "cash_denomination", precision = 19, scale = 2)
    private BigDecimal cashDenomination;

    @Column(name = "transfer_receipt_path", length = 1024)
    private String transferReceiptPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected ConversationSession() {
        // Required by JPA.
    }

    private ConversationSession(String phoneNumber, Instant now) {
        this.phoneNumber = Objects.requireNonNull(phoneNumber, "phoneNumber must not be null");
        this.state = ConversationState.ORDERING;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
        this.lastActivityAt = now;
    }

    public static ConversationSession create(String phoneNumber, Instant now) {
        return new ConversationSession(phoneNumber, now);
    }

    public void recordActivity(Instant now) {
        touch(now);
    }

    public void recordTransferReceipt(String receiptPath, Instant now) {
        this.transferReceiptPath = Objects.requireNonNull(receiptPath, "receiptPath must not be null");
        touch(now);
    }

    public void reset(Instant now) {
        this.state = ConversationState.ORDERING;
        this.fulfillmentType = null;
        this.deliveryAddress = null;
        this.pickupName = null;
        this.paymentMethod = null;
        this.cashDenomination = null;
        this.transferReceiptPath = null;
        touch(now);
    }

    /*
     * These package-private capture methods persist future deterministic inputs.
     * They intentionally do not infer or transition checkout state.
     */
    void captureCheckoutDetails(FulfillmentType fulfillmentType,
                                String deliveryAddress,
                                String pickupName,
                                PaymentMethod paymentMethod,
                                BigDecimal cashDenomination,
                                Instant now) {
        this.fulfillmentType = fulfillmentType;
        this.deliveryAddress = deliveryAddress;
        this.pickupName = pickupName;
        this.paymentMethod = paymentMethod;
        this.cashDenomination = cashDenomination;
        touch(now);
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public ConversationState getState() {
        return state;
    }

    public FulfillmentType getFulfillmentType() {
        return fulfillmentType;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public String getPickupName() {
        return pickupName;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public BigDecimal getCashDenomination() {
        return cashDenomination;
    }

    public String getTransferReceiptPath() {
        return transferReceiptPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Long getVersion() {
        return version;
    }

    private void touch(Instant now) {
        Instant timestamp = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = timestamp;
        this.lastActivityAt = timestamp;
    }
}
