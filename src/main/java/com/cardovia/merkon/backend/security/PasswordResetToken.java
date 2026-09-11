package com.cardovia.merkon.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** One-time password-recovery evidence. Only the SHA-256 token hash is durable. */
@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PasswordResetToken() {
    }

    static PasswordResetToken issue(AppUser user, String tokenHash, Instant createdAt, Instant expiresAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.user = Objects.requireNonNull(user);
        token.tokenHash = Objects.requireNonNull(tokenHash);
        token.createdAt = Objects.requireNonNull(createdAt);
        token.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Password reset token expiry must be after creation");
        }
        return token;
    }

    TokenUseResult consume(Instant now) {
        if (usedAt != null) {
            return TokenUseResult.USED;
        }
        if (revokedAt != null) {
            return TokenUseResult.REVOKED;
        }
        if (!now.isBefore(expiresAt)) {
            return TokenUseResult.EXPIRED;
        }
        usedAt = now;
        return TokenUseResult.CONSUMED;
    }

    void revoke(Instant now) {
        if (usedAt == null && revokedAt == null) {
            revokedAt = Objects.requireNonNull(now);
        }
    }

    Long getId() { return id; }
    AppUser getUser() { return user; }
    String getTokenHash() { return tokenHash; }
    Instant getCreatedAt() { return createdAt; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getUsedAt() { return usedAt; }
    Instant getRevokedAt() { return revokedAt; }

    enum TokenUseResult {
        CONSUMED,
        USED,
        REVOKED,
        EXPIRED
    }
}
