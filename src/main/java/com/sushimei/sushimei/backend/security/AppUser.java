package com.sushimei.sushimei.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationRole role;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AppUser() {
    }

    static AppUser create(String username, String displayName, String passwordHash, ApplicationRole role, Instant now) {
        AppUser user = new AppUser();
        user.username = Objects.requireNonNull(username);
        user.displayName = Objects.requireNonNull(displayName);
        user.passwordHash = Objects.requireNonNull(passwordHash);
        user.role = Objects.requireNonNull(role);
        user.active = true;
        user.passwordChangedAt = now;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    void recordFailure(Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= 10) {
            lockedUntil = now.plusSeconds(15 * 60L);
        } else if (failedLoginAttempts >= 8) {
            lockedUntil = now.plusSeconds(5 * 60L);
        } else if (failedLoginAttempts >= 5) {
            lockedUntil = now.plusSeconds(30L);
        }
        updatedAt = now;
    }

    void recordSuccess(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    void update(String displayName, ApplicationRole role, boolean active, Instant now) {
        this.displayName = displayName;
        this.role = role;
        this.active = active;
        this.updatedAt = now;
    }

    void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public ApplicationRole getRole() { return role; }
    public boolean isActive() { return active; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public long getVersion() { return version; }
}