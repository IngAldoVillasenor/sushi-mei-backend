package com.cardovia.merkon.backend.security;

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

    @Column(nullable = false, length = 254, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(length = 254, unique = true)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_state", nullable = false, length = 40)
    private AccountRegistrationState registrationState;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    /**
     * Compatibility snapshot retained for pre-membership data and the narrow
     * single-business administration path. Business authorization must use
     * {@link com.cardovia.merkon.backend.business.BusinessMembership#getRole()}.
     */
    private ApplicationRole legacyRole;

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

    static AppUser create(String username, String displayName, String passwordHash, ApplicationRole legacyRole, Instant now) {
        AppUser user = new AppUser();
        user.username = Objects.requireNonNull(username);
        user.displayName = Objects.requireNonNull(displayName);
        user.passwordHash = Objects.requireNonNull(passwordHash);
        user.legacyRole = Objects.requireNonNull(legacyRole);
        user.registrationState = AccountRegistrationState.LEGACY;
        user.active = true;
        user.passwordChangedAt = now;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /**
     * Creates the deliberately inactive identity used by the public
     * registration flow. SCRUM-56 is responsible for the later verified
     * activation transition; this method must never create a login-eligible
     * account.
     */
    static AppUser createPendingRegistration(String normalizedEmail,
                                             String displayName,
                                             String passwordHash,
                                             Instant now) {
        AppUser user = new AppUser();
        user.username = Objects.requireNonNull(normalizedEmail);
        user.displayName = Objects.requireNonNull(displayName);
        user.email = normalizedEmail;
        user.passwordHash = Objects.requireNonNull(passwordHash);
        // The non-null legacy column remains a compatibility snapshot only.
        // The new business authorization authority is the OWNER membership.
        user.legacyRole = ApplicationRole.OWNER;
        user.registrationState = AccountRegistrationState.PENDING_EMAIL_VERIFICATION;
        user.active = false;
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

    void updateLegacyAdministrativeState(String displayName, ApplicationRole legacyRole, boolean active, Instant now) {
        this.displayName = displayName;
        this.legacyRole = legacyRole;
        this.active = active;
        this.updatedAt = now;
    }

    void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies a password chosen through a successfully consumed public reset
     * token. This is deliberately not a login: it clears stale lockout state
     * without recording a fictitious successful sign-in.
     */
    void resetPassword(String passwordHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.passwordChangedAt = Objects.requireNonNull(now);
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    void setNormalizedEmail(String email, AccountRegistrationState registrationState, Instant now) {
        this.email = email;
        this.registrationState = Objects.requireNonNull(registrationState);
        this.emailVerifiedAt = registrationState == AccountRegistrationState.ACTIVE ? now : null;
        this.updatedAt = now;
    }

    /** Consumes the only valid public-registration activation transition. */
    void activateVerifiedRegistration(Instant now) {
        if (registrationState != AccountRegistrationState.PENDING_EMAIL_VERIFICATION || active
                || email == null || email.isBlank() || emailVerifiedAt != null) {
            throw new IllegalStateException("Account is not eligible for verified activation");
        }
        registrationState = AccountRegistrationState.ACTIVE;
        active = true;
        emailVerifiedAt = Objects.requireNonNull(now);
        // Pending users may have attempted to sign in before completing the
        // email step. Activation is not a login, but it must clear that
        // pre-verification lock state so the valid existing password works.
        failedLoginAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Irreversibly removes identifying and login-capable account state. */
    void anonymizeDeleted(String tombstoneUsername, String unusablePasswordHash, Instant now) {
        this.username = Objects.requireNonNull(tombstoneUsername);
        this.displayName = "Cuenta eliminada";
        this.email = null;
        this.emailVerifiedAt = null;
        this.passwordHash = Objects.requireNonNull(unusablePasswordHash);
        this.registrationState = AccountRegistrationState.DELETED;
        this.active = false;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.lastLoginAt = null;
        this.passwordChangedAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public AccountRegistrationState getRegistrationState() { return registrationState; }
    public String getPasswordHash() { return passwordHash; }
    public ApplicationRole getLegacyRole() { return legacyRole; }
    public boolean isActive() { return active; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public long getVersion() { return version; }
}
