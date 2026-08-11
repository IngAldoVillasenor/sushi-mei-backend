package com.sushimei.sushimei.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "device_id", nullable = false, length = 120)
    private String deviceId;

    @Column(name = "device_name", length = 160)
    private String deviceName;

    @Column(name = "app_version", length = 40)
    private String appVersion;

    @Column(name = "current_refresh_token_hash", nullable = false, length = 64, unique = true)
    private String currentRefreshTokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 80)
    private String revokeReason;

    protected AuthSession() {
    }

    static AuthSession create(UUID id,
                              AppUser user,
                              String deviceId,
                              String deviceName,
                              String appVersion,
                              String tokenHash,
                              Instant now,
                              Instant absoluteExpiresAt) {
        AuthSession session = new AuthSession();
        session.id = id;
        session.user = user;
        session.deviceId = deviceId;
        session.deviceName = deviceName;
        session.appVersion = appVersion;
        session.currentRefreshTokenHash = tokenHash;
        session.createdAt = now;
        session.lastRefreshedAt = now;
        session.absoluteExpiresAt = absoluteExpiresAt;
        return session;
    }

    void rotate(String tokenHash, Instant now) {
        currentRefreshTokenHash = tokenHash;
        lastRefreshedAt = now;
    }

    void revoke(String reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }

    public UUID getId() { return id; }
    public AppUser getUser() { return user; }
    public String getDeviceId() { return deviceId; }
    public String getDeviceName() { return deviceName; }
    public String getAppVersion() { return appVersion; }
    public String getCurrentRefreshTokenHash() { return currentRefreshTokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastRefreshedAt() { return lastRefreshedAt; }
    public Instant getAbsoluteExpiresAt() { return absoluteExpiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public boolean isRevoked() { return revokedAt != null; }
}