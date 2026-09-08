package com.cardovia.merkon.backend.business;

import com.cardovia.merkon.backend.security.AppUser;
import com.cardovia.merkon.backend.security.ApplicationRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "business_memberships", uniqueConstraints = @UniqueConstraint(
        name = "business_memberships_user_business_key", columnNames = {"user_id", "business_id"}))
public class BusinessMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BusinessMembership() {
    }

    public static BusinessMembership create(AppUser user, Business business, ApplicationRole role, Instant now) {
        BusinessMembership membership = new BusinessMembership();
        membership.user = Objects.requireNonNull(user);
        membership.business = Objects.requireNonNull(business);
        membership.role = Objects.requireNonNull(role);
        membership.createdAt = now;
        membership.updatedAt = now;
        return membership;
    }

    public void updateRole(ApplicationRole role, Instant now) {
        this.role = Objects.requireNonNull(role);
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public Business getBusiness() { return business; }
    public ApplicationRole getRole() { return role; }
    public long getVersion() { return version; }
}
