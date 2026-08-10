package com.sushimei.sushimei.backend.promotion;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "promotions")
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false, length = 32)
    private PromotionBenefitType benefitType;

    @Column(name = "fixed_unit_price_amount", precision = 19, scale = 2)
    private BigDecimal fixedUnitPriceAmount;

    @Column(name = "buy_quantity")
    private Integer buyQuantity;

    @Column(name = "reward_quantity")
    private Integer rewardQuantity;

    @Column(name = "repeat_enabled")
    private Boolean repeatEnabled;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @ElementCollection(fetch = FetchType.LAZY)
    @Column(name = "iso_day_of_week", nullable = false)
    @jakarta.persistence.CollectionTable(name = "promotion_weekdays", joinColumns = @jakarta.persistence.JoinColumn(name = "promotion_id"))
    private Set<Integer> isoWeekdays = new LinkedHashSet<>();

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PromotionTarget> targets = new java.util.ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Promotion() {
        // JPA
    }

    static Promotion create(String name,
                            boolean active,
                            int priority,
                            PromotionBenefitType benefitType,
                            BigDecimal fixedUnitPriceAmount,
                            Integer buyQuantity,
                            Integer rewardQuantity,
                            Boolean repeatEnabled,
                            LocalDate validFrom,
                            LocalDate validUntil,
                            Set<Integer> isoWeekdays,
                            List<PromotionTargetDraft> targets,
                            Instant now) {
        Promotion promotion = new Promotion();
        promotion.apply(name, active, priority, benefitType, fixedUnitPriceAmount, buyQuantity, rewardQuantity,
                repeatEnabled, validFrom, validUntil, isoWeekdays, targets, now);
        promotion.createdAt = now;
        return promotion;
    }

    void update(String name,
                boolean active,
                int priority,
                PromotionBenefitType benefitType,
                BigDecimal fixedUnitPriceAmount,
                Integer buyQuantity,
                Integer rewardQuantity,
                Boolean repeatEnabled,
                LocalDate validFrom,
                LocalDate validUntil,
                Set<Integer> isoWeekdays,
                List<PromotionTargetDraft> targets,
                Instant now) {
        apply(name, active, priority, benefitType, fixedUnitPriceAmount, buyQuantity, rewardQuantity,
                repeatEnabled, validFrom, validUntil, isoWeekdays, targets, now);
    }

    void archive(Instant now) {
        active = false;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private void apply(String name,
                       boolean active,
                       int priority,
                       PromotionBenefitType benefitType,
                       BigDecimal fixedUnitPriceAmount,
                       Integer buyQuantity,
                       Integer rewardQuantity,
                       Boolean repeatEnabled,
                       LocalDate validFrom,
                       LocalDate validUntil,
                       Set<Integer> isoWeekdays,
                       List<PromotionTargetDraft> targetDrafts,
                       Instant now) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.active = active;
        this.priority = priority;
        this.benefitType = Objects.requireNonNull(benefitType, "benefitType must not be null");
        this.fixedUnitPriceAmount = fixedUnitPriceAmount;
        this.buyQuantity = buyQuantity;
        this.rewardQuantity = rewardQuantity;
        this.repeatEnabled = repeatEnabled;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.isoWeekdays.clear();
        this.isoWeekdays.addAll(Objects.requireNonNull(isoWeekdays, "isoWeekdays must not be null"));
        this.targets.clear();
        for (PromotionTargetDraft target : Objects.requireNonNull(targetDrafts, "targets must not be null")) {
            this.targets.add(PromotionTarget.create(this, target.targetMenuItem(), target.targetTag()));
        }
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public int getPriority() { return priority; }
    public PromotionBenefitType getBenefitType() { return benefitType; }
    public BigDecimal getFixedUnitPriceAmount() { return fixedUnitPriceAmount; }
    public Integer getBuyQuantity() { return buyQuantity; }
    public Integer getRewardQuantity() { return rewardQuantity; }
    public Boolean getRepeatEnabled() { return repeatEnabled; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public Set<Integer> getIsoWeekdays() { return Set.copyOf(isoWeekdays); }
    public List<PromotionTarget> getTargets() { return List.copyOf(targets); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
