package com.cardovia.merkon.backend.catalog;

import com.cardovia.merkon.backend.business.Business;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "catalog_tags")
public class CatalogTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false, updatable = false)
    private Business business;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CatalogTag() {
        // JPA
    }

    static CatalogTag create(String code, String name, int displayOrder, Instant now) {
        CatalogTag tag = new CatalogTag();
        tag.code = Objects.requireNonNull(code, "code must not be null");
        tag.name = Objects.requireNonNull(name, "name must not be null");
        tag.active = true;
        tag.displayOrder = displayOrder;
        tag.createdAt = Objects.requireNonNull(now, "now must not be null");
        tag.updatedAt = now;
        return tag;
    }

    public void assignBusiness(Business business) {
        Business required = Objects.requireNonNull(business, "business must not be null");
        if (this.business != null && !this.business.getId().equals(required.getId())) {
            throw new IllegalStateException("Catalog tag business cannot change");
        }
        this.business = required;
    }

    void update(String name, boolean active, int displayOrder, Instant now) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.active = active;
        this.displayOrder = displayOrder;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    void archive(Instant now) {
        this.active = false;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() {
        return id;
    }

    public Business getBusiness() { return business; }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
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
