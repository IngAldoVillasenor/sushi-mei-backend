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

/** One-time public-email verification evidence. Only the SHA-256 token hash is durable. */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

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

    protected EmailVerificationToken() {
    }

    static EmailVerificationToken issue(AppUser user, String tokenHash, Instant createdAt, Instant expiresAt) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.user = Objects.requireNonNull(user);
        token.tokenHash = Objects.requireNonNull(tokenHash);
        token.createdAt = Objects.requireNonNull(createdAt);
        token.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Verification token expiry must be after creation");
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

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    enum TokenUseResult {
        CONSUMED,
        USED,
        REVOKED,
        EXPIRED
    }
}
