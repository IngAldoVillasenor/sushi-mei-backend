package com.sushimei.sushimei.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Data
@Entity
@Table(name = "orders")
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phoneNumber;

    private String deliveryType;

    @Column(length = 500)
    private String deliveryAddress;

    @Column(length = 1024)
    String transferReceiptPath;

    private String paymentNotes;

    @Column(columnDefinition = "TEXT")
    private String orderDetails;

    private Double totalAmount;

    @JsonIgnore
    @Column(name = "total_amount_amount", precision = 19, scale = 2)
    private BigDecimal totalAmountAmount;

    /**
     * Nullable provenance for historical-order compatibility. There is
     * intentionally no cart foreign key: a structured order must outlive later
     * cart lifecycle changes while the unique database constraint remains the
     * idempotency boundary for non-null values.
     */
    @JsonIgnore
    @Column(name = "source_cart_id")
    private Long sourceCartId;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(name = "order_source")
    private OrderSource orderSource;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type")
    private OrderFulfillmentType fulfillmentType;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private OrderPaymentMethod paymentMethod;

    @JsonIgnore
    @Column(name = "pickup_name", length = 120)
    private String pickupName;

    @JsonIgnore
    @Column(name = "cash_denomination", precision = 19, scale = 2)
    private BigDecimal cashDenomination;

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("linePosition ASC")
    private List<OrderLineRecord> orderLines = new ArrayList<>();

    private String status;

    private LocalDateTime createdAt;

    public void addOrderLine(OrderLineRecord line) {
        OrderLineRecord structuredLine = Objects.requireNonNull(line, "line must not be null");
        structuredLine.attachTo(this);
        orderLines.add(structuredLine);
    }

    public List<OrderLineRecord> getOrderLines() {
        return List.copyOf(orderLines);
    }
}
