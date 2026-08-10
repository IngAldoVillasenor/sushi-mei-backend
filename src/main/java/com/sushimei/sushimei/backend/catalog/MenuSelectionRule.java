package com.sushimei.sushimei.backend.catalog;

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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "menu_selection_rules")
public class MenuSelectionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "selection_group_id", nullable = false)
    private MenuSelectionGroup selectionGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_menu_item_id")
    private MenuItem targetMenuItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_tag_id")
    private CatalogTag targetTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_policy", nullable = false, length = 32)
    private SelectionPricingPolicy pricingPolicy;

    @Column(name = "reference_price_amount", precision = 19, scale = 2)
    private BigDecimal referencePriceAmount;

    @Column(name = "fixed_surcharge_amount", precision = 19, scale = 2)
    private BigDecimal fixedSurchargeAmount;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MenuSelectionRule() {
        // JPA
    }

    static MenuSelectionRule create(MenuSelectionGroup selectionGroup,
                                    MenuItem targetMenuItem,
                                    CatalogTag targetTag,
                                    SelectionPricingPolicy pricingPolicy,
                                    BigDecimal referencePriceAmount,
                                    BigDecimal fixedSurchargeAmount,
                                    int priority,
                                    Instant now) {
        MenuSelectionRule rule = new MenuSelectionRule();
        rule.selectionGroup = Objects.requireNonNull(selectionGroup, "selectionGroup must not be null");
        rule.targetMenuItem = targetMenuItem;
        rule.targetTag = targetTag;
        rule.pricingPolicy = Objects.requireNonNull(pricingPolicy, "pricingPolicy must not be null");
        rule.referencePriceAmount = referencePriceAmount;
        rule.fixedSurchargeAmount = fixedSurchargeAmount;
        rule.priority = priority;
        rule.active = true;
        rule.createdAt = Objects.requireNonNull(now, "now must not be null");
        rule.updatedAt = now;
        return rule;
    }

    void update(MenuItem targetMenuItem,
                CatalogTag targetTag,
                SelectionPricingPolicy pricingPolicy,
                BigDecimal referencePriceAmount,
                BigDecimal fixedSurchargeAmount,
                int priority,
                boolean active,
                Instant now) {
        this.targetMenuItem = targetMenuItem;
        this.targetTag = targetTag;
        this.pricingPolicy = Objects.requireNonNull(pricingPolicy, "pricingPolicy must not be null");
        this.referencePriceAmount = referencePriceAmount;
        this.fixedSurchargeAmount = fixedSurchargeAmount;
        this.priority = priority;
        this.active = active;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    void archive(Instant now) {
        this.active = false;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() {
        return id;
    }

    public MenuSelectionGroup getSelectionGroup() {
        return selectionGroup;
    }

    public MenuItem getTargetMenuItem() {
        return targetMenuItem;
    }

    public CatalogTag getTargetTag() {
        return targetTag;
    }

    public SelectionPricingPolicy getPricingPolicy() {
        return pricingPolicy;
    }

    public BigDecimal getReferencePriceAmount() {
        return referencePriceAmount;
    }

    public BigDecimal getFixedSurchargeAmount() {
        return fixedSurchargeAmount;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
