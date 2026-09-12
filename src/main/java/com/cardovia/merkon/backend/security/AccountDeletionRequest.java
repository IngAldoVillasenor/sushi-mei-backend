package com.cardovia.merkon.backend.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_deletion_requests")
class AccountDeletionRequest {
    @Id private UUID id;
    @ManyToOne(optional = false) @JoinColumn(name = "user_id") private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AccountDeletionSource source;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AccountDeletionStatus status;
    @Column(name = "token_hash", length = 64, unique = true) private String tokenHash;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "action_code", length = 80) private String actionCode;
    protected AccountDeletionRequest() { }
    static AccountDeletionRequest pending(AppUser user, AccountDeletionSource source, String tokenHash, Instant now, Instant expiresAt) {
        AccountDeletionRequest request = new AccountDeletionRequest();
        request.id = UUID.randomUUID(); request.user = user; request.source = source; request.status = AccountDeletionStatus.PENDING_CONFIRMATION;
        request.tokenHash = tokenHash; request.requestedAt = now; request.expiresAt = expiresAt; return request;
    }
    /**
     * Superseding a link is not a confirmation. In particular, preserve a
     * historical confirmation timestamp if a future lifecycle ever needs to
     * supersede an already-confirmed record rather than fabricate one here.
     */
    void supersede(Instant now) {
        status = AccountDeletionStatus.SUPERSEDED;
        tokenHash = null;
        completedAt = null;
    }
    boolean consumeIfUsable(Instant now) {
        if (status != AccountDeletionStatus.PENDING_CONFIRMATION || tokenHash == null || expiresAt == null || !now.isBefore(expiresAt)) return false;
        confirmedAt = now; tokenHash = null; return true;
    }
    void confirmInApp(Instant now) { confirmedAt = now; expiresAt = null; tokenHash = null; }
    void complete(Instant now) { status = AccountDeletionStatus.COMPLETED; completedAt = now; }
    void actionRequired(String code, Instant now) { status = AccountDeletionStatus.ACTION_REQUIRED; actionCode = code; tokenHash = null; completedAt = null; }
    UUID getId() { return id; }
    AppUser getUser() { return user; }
    String getTokenHash() { return tokenHash; }
    AccountDeletionSource getSource() { return source; }
    AccountDeletionStatus getStatus() { return status; }
    Instant getRequestedAt() { return requestedAt; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getConfirmedAt() { return confirmedAt; }
    Instant getCompletedAt() { return completedAt; }
    String getActionCode() { return actionCode; }
}
