package com.sushimei.sushimei.backend.entity;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "order_lines")
public class OrderLineRecord {

    private static final int MAX_DISH_NAME_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_product_reference", length = 120)
    private String externalProductReference;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderRecord order;

    @Column(name = "source_cart_item_id", updatable = false)
    private Long sourceCartItemId;

    @Column(name = "source_menu_item_id", updatable = false)
    private Long sourceMenuItemId;

    /** Immutable Android request-line correlation for a manual paid line. */
    @Column(name = "client_line_key", length = 120, updatable = false)
    private String clientLineKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_kind", nullable = false, length = 32, updatable = false)
    private OrderLineKind lineKind;

    @Column(name = "line_position", nullable = false, updatable = false)
    private int linePosition;

    @Column(name = "dish_name", nullable = false, length = MAX_DISH_NAME_LENGTH, updatable = false)
    private String dishName;

    @Column(nullable = false, updatable = false)
    private int quantity;

    /** Final charged unit amount, not a mutable catalog price. */
    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal unitPriceAmount;

    @Column(name = "line_total_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal lineTotalAmount;

    @Column(name = "catalog_base_unit_price", precision = 19, scale = 2, updatable = false)
    private BigDecimal catalogBaseUnitPrice;

    @Column(name = "charged_base_unit_price", precision = 19, scale = 2, updatable = false)
    private BigDecimal chargedBaseUnitPrice;

    @Column(name = "configuration_adjustment_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal configurationAdjustmentAmount;

    @Column(name = "applied_promotion_id", updatable = false)
    private Long appliedPromotionId;

    @Column(name = "applied_promotion_name", length = 160, updatable = false)
    private String appliedPromotionName;

    @Column(name = "applied_promotion_benefit_type", length = 32, updatable = false)
    private String appliedPromotionBenefitType;

    @Column(name = "reward_ordinal", updatable = false)
    private Integer rewardOrdinal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_paid_line_id", updatable = false)
    private OrderLineRecord sourcePaidLine;

    @OneToMany(mappedBy = "orderLine", cascade = CascadeType.PERSIST)
    @OrderBy("id ASC")
    private List<OrderLineSelectionSnapshot> selectionSnapshots = new ArrayList<>();

    protected OrderLineRecord() {
        // JPA
    }

    /** Existing deterministic-cart factory retained for Phase 5B compatibility. */
    public static OrderLineRecord create(Long sourceCartItemId,
                                         int linePosition,
                                         String dishName,
                                         int quantity,
                                         BigDecimal unitPriceAmount,
                                         BigDecimal lineTotalAmount) {
        return new OrderLineRecord(
                Objects.requireNonNull(sourceCartItemId, "sourceCartItemId must not be null"),
                null,
                null,
                OrderLineKind.PAID,
                linePosition,
                dishName,
                quantity,
                unitPriceAmount,
                lineTotalAmount,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }


    public static OrderLineRecord createExternalHistoricalPaid(
            String externalProductReference,
            int linePosition,
            String dishName,
            int quantity,
            BigDecimal catalogBaseUnitPrice,
            BigDecimal chargedBaseUnitPrice,
            BigDecimal configurationAdjustmentAmount,
            BigDecimal finalUnitAmount,
            BigDecimal finalLineTotal,
            Long appliedPromotionId,
            String appliedPromotionName,
            String appliedPromotionBenefitType) {
        OrderLineRecord record = new OrderLineRecord(
                null,
                null,
                null,
                OrderLineKind.PAID,
                linePosition,
                dishName,
                quantity,
                finalUnitAmount,
                finalLineTotal,
                catalogBaseUnitPrice,
                chargedBaseUnitPrice,
                configurationAdjustmentAmount,
                appliedPromotionId,
                appliedPromotionName,
                appliedPromotionBenefitType,
                null,
                null);
        record.setExternalProductReference(requireNonBlank(externalProductReference, "externalProductReference"));
        return record;
    }

    public static OrderLineRecord createManualPaid(String clientLineKey,
                                                   Long sourceMenuItemId,
                                                   int linePosition,
                                                   String dishName,
                                                   int quantity,
                                                   BigDecimal catalogBaseUnitPrice,
                                                   BigDecimal chargedBaseUnitPrice,
                                                   BigDecimal configurationAdjustmentAmount,
                                                   BigDecimal finalUnitAmount,
                                                   BigDecimal finalLineTotal,
                                                   Long appliedPromotionId,
                                                   String appliedPromotionName,
                                                   String appliedPromotionBenefitType) {
        return new OrderLineRecord(
                null,
                requirePositiveId(sourceMenuItemId, "sourceMenuItemId"),
                requireClientLineKey(clientLineKey),
                OrderLineKind.PAID,
                linePosition,
                dishName,
                quantity,
                finalUnitAmount,
                finalLineTotal,
                requireNonNegativeAmount(catalogBaseUnitPrice, "catalogBaseUnitPrice"),
                requireNonNegativeAmount(chargedBaseUnitPrice, "chargedBaseUnitPrice"),
                requireNonNegativeAmount(configurationAdjustmentAmount, "configurationAdjustmentAmount"),
                appliedPromotionId,
                appliedPromotionName,
                appliedPromotionBenefitType,
                null,
                null);
    }

    public static OrderLineRecord createPromotionReward(OrderLineRecord sourcePaidLine,
                                                        Long sourceMenuItemId,
                                                        int linePosition,
                                                        String dishName,
                                                        BigDecimal catalogBaseUnitPrice,
                                                        BigDecimal configurationAdjustmentAmount,
                                                        BigDecimal finalUnitAmount,
                                                        BigDecimal finalLineTotal,
                                                        Long appliedPromotionId,
                                                        String appliedPromotionName,
                                                        String appliedPromotionBenefitType,
                                                        int rewardOrdinal) {
        OrderLineRecord source = Objects.requireNonNull(sourcePaidLine, "sourcePaidLine must not be null");
        return new OrderLineRecord(
                null,
                requirePositiveId(sourceMenuItemId, "sourceMenuItemId"),
                null,
                OrderLineKind.PROMOTION_REWARD,
                linePosition,
                dishName,
                1,
                finalUnitAmount,
                finalLineTotal,
                requirePositiveAmount(catalogBaseUnitPrice, "catalogBaseUnitPrice"),
                BigDecimal.ZERO.setScale(CheckoutMoney.SCALE),
                requireNonNegativeAmount(configurationAdjustmentAmount, "configurationAdjustmentAmount"),
                Objects.requireNonNull(appliedPromotionId, "appliedPromotionId must not be null"),
                requireNonBlank(appliedPromotionName, "appliedPromotionName"),
                requireNonBlank(appliedPromotionBenefitType, "appliedPromotionBenefitType"),
                requirePositive(rewardOrdinal, "rewardOrdinal"),
                source);
    }

    public static OrderLineRecord createPromotionReward(OrderLineRecord sourcePaidLine,
                                                        int linePosition,
                                                        String dishName,
                                                        BigDecimal catalogBaseUnitPrice,
                                                        BigDecimal configurationAdjustmentAmount,
                                                        BigDecimal finalUnitAmount,
                                                        BigDecimal finalLineTotal,
                                                        Long appliedPromotionId,
                                                        String appliedPromotionName,
                                                        String appliedPromotionBenefitType,
                                                        int rewardOrdinal) {
        return createPromotionReward(sourcePaidLine, sourcePaidLine.getSourceMenuItemId(), linePosition, dishName,
                catalogBaseUnitPrice, configurationAdjustmentAmount, finalUnitAmount, finalLineTotal,
                appliedPromotionId, appliedPromotionName, appliedPromotionBenefitType, rewardOrdinal);
    }

    private OrderLineRecord(Long sourceCartItemId,
                            Long sourceMenuItemId,
                            String clientLineKey,
                            OrderLineKind lineKind,
                            int linePosition,
                            String dishName,
                            int quantity,
                            BigDecimal unitPriceAmount,
                            BigDecimal lineTotalAmount,
                            BigDecimal catalogBaseUnitPrice,
                            BigDecimal chargedBaseUnitPrice,
                            BigDecimal configurationAdjustmentAmount,
                            Long appliedPromotionId,
                            String appliedPromotionName,
                            String appliedPromotionBenefitType,
                            Integer rewardOrdinal,
                            OrderLineRecord sourcePaidLine) {
        this.sourceCartItemId = sourceCartItemId;
        this.sourceMenuItemId = sourceMenuItemId;
        this.clientLineKey = clientLineKey;
        this.lineKind = Objects.requireNonNull(lineKind, "lineKind must not be null");
        this.linePosition = requirePositive(linePosition, "linePosition");
        this.dishName = normalizeDishName(dishName);
        this.quantity = requirePositive(quantity, "quantity");
        this.unitPriceAmount = lineKind == OrderLineKind.PAID
                ? requirePositiveAmount(unitPriceAmount, "unitPriceAmount")
                : requireNonNegativeAmount(unitPriceAmount, "unitPriceAmount");
        this.lineTotalAmount = lineKind == OrderLineKind.PAID
                ? requirePositiveAmount(lineTotalAmount, "lineTotalAmount")
                : requireNonNegativeAmount(lineTotalAmount, "lineTotalAmount");
        requireLineTotal(this.quantity, this.unitPriceAmount, this.lineTotalAmount);
        this.catalogBaseUnitPrice = catalogBaseUnitPrice;
        this.chargedBaseUnitPrice = chargedBaseUnitPrice;
        this.configurationAdjustmentAmount = configurationAdjustmentAmount;
        this.appliedPromotionId = appliedPromotionId;
        this.appliedPromotionName = appliedPromotionName;
        this.appliedPromotionBenefitType = appliedPromotionBenefitType;
        this.rewardOrdinal = rewardOrdinal;
        this.sourcePaidLine = sourcePaidLine;
    }

    void attachTo(OrderRecord order) {
        this.order = Objects.requireNonNull(order, "order must not be null");
    }

    public void addSelectionSnapshot(OrderLineSelectionSnapshot snapshot) {
        OrderLineSelectionSnapshot selection = Objects.requireNonNull(snapshot, "snapshot must not be null");
        selection.attachTo(this);
        selectionSnapshots.add(selection);
    }

    public Long getId() { return id; }
    public String getExternalProductReference() { return externalProductReference; }
    public void setExternalProductReference(String externalProductReference) { this.externalProductReference = externalProductReference; }
    public Long getSourceCartItemId() { return sourceCartItemId; }
    public Long getSourceMenuItemId() { return sourceMenuItemId; }
    public String getClientLineKey() { return clientLineKey; }
    public OrderLineKind getLineKind() { return lineKind; }
    public int getLinePosition() { return linePosition; }
    public String getDishName() { return dishName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPriceAmount() { return unitPriceAmount; }
    public BigDecimal getLineTotalAmount() { return lineTotalAmount; }
    public BigDecimal getCatalogBaseUnitPrice() { return catalogBaseUnitPrice; }
    public BigDecimal getChargedBaseUnitPrice() { return chargedBaseUnitPrice; }
    public BigDecimal getConfigurationAdjustmentAmount() { return configurationAdjustmentAmount; }
    public Long getAppliedPromotionId() { return appliedPromotionId; }
    public String getAppliedPromotionName() { return appliedPromotionName; }
    public String getAppliedPromotionBenefitType() { return appliedPromotionBenefitType; }
    public Integer getRewardOrdinal() { return rewardOrdinal; }
    public OrderLineRecord getSourcePaidLine() { return sourcePaidLine; }
    public List<OrderLineSelectionSnapshot> getSelectionSnapshots() { return List.copyOf(selectionSnapshots); }

    private static void requireLineTotal(int quantity, BigDecimal unitAmount, BigDecimal totalAmount) {
        BigDecimal expected = unitAmount.multiply(BigDecimal.valueOf(quantity)).setScale(CheckoutMoney.SCALE, RoundingMode.UNNECESSARY);
        if (expected.compareTo(totalAmount) != 0) {
            throw new IllegalArgumentException("lineTotalAmount must equal quantity multiplied by unitPriceAmount");
        }
    }

    private static Long requirePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
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

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requireClientLineKey(String value) {
        String normalized = requireNonBlank(value, "clientLineKey");
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("clientLineKey is outside the supported length");
        }
        return normalized;
    }

    private static BigDecimal requirePositiveAmount(BigDecimal value, String fieldName) {
        BigDecimal normalized = requireNonNegativeAmount(value, fieldName);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }

    private static BigDecimal requireNonNegativeAmount(BigDecimal value, String fieldName) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > CheckoutMoney.SCALE) {
            throw new IllegalArgumentException(fieldName + " must be an exact non-negative checkout amount");
        }
        BigDecimal normalized = value.setScale(CheckoutMoney.SCALE, RoundingMode.UNNECESSARY);
        if (normalized.precision() > CheckoutMoney.PRECISION) {
            throw new IllegalArgumentException(fieldName + " exceeds checkout precision");
        }
        return normalized;
    }
}
