package com.sushimei.sushimei.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Operational menu data. This aggregate is intentionally separate from
 * LangChain4j's derived menu_embeddings projection.
 */
@Entity
@Table(name = "menu_items")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 120)
    private String category;

    @Column(name = "price_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal priceAmount;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MenuItem() {
        // JPA
    }

    static MenuItem create(String name,
                           String description,
                           String category,
                           BigDecimal priceAmount,
                           boolean available,
                           int displayOrder,
                           Instant now) {
        MenuItem item = new MenuItem();
        item.name = Objects.requireNonNull(name, "name must not be null");
        item.description = description;
        item.category = Objects.requireNonNull(category, "category must not be null");
        item.priceAmount = Objects.requireNonNull(priceAmount, "priceAmount must not be null");
        item.active = true;
        item.available = available;
        item.displayOrder = displayOrder;
        item.createdAt = Objects.requireNonNull(now, "now must not be null");
        item.updatedAt = now;
        return item;
    }

    void update(String name,
                String description,
                String category,
                BigDecimal priceAmount,
                boolean active,
                boolean available,
                int displayOrder,
                Instant now) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.priceAmount = Objects.requireNonNull(priceAmount, "priceAmount must not be null");
        this.active = active;
        this.available = available;
        this.displayOrder = displayOrder;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    void archive(Instant now) {
        this.active = false;
        this.available = false;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isAvailable() {
        return available;
    }

    public int getDisplayOrder() {
        return displayOrder;
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
