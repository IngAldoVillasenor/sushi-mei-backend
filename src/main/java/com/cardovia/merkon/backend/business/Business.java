package com.cardovia.merkon.backend.business;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "businesses")
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    /**
     * Stable server-side binding for a legacy integration. It is deliberately
     * not a display name and is null for normal businesses.
     */
    @Column(name = "legacy_key", length = 64, unique = true)
    private String legacyKey;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Business() {
    }

    public static Business create(String name, Instant now) {
        Business business = new Business();
        business.name = Objects.requireNonNull(name);
        business.active = true;
        business.createdAt = now;
        business.updatedAt = now;
        return business;
    }

    public void deactivate(Instant now) {
        active = false;
        updatedAt = Objects.requireNonNull(now);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getLegacyKey() { return legacyKey; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
}
