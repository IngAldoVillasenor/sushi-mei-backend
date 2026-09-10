package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-administration operations. The caller's business is always derived
 * from its authenticated session context; callers never select a target tenant.
 */
@Service
public class UserManagementService {

    private final AppUserRepository users;
    private final BusinessMembershipRepository memberships;
    private final PasswordPolicyService passwords;
    private final AuthSessionService sessions;
    private final SecurityAuditService audit;
    private final Clock clock;

    public UserManagementService(AppUserRepository users,
                                 BusinessMembershipRepository memberships,
                                 PasswordPolicyService passwords,
                                 AuthSessionService sessions,
                                 SecurityAuditService audit,
                                 Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.passwords = passwords;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(Long actorUserId, Long activeBusinessId) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        return memberships.findByBusinessIdOrderByIdAsc(actorMembership.getBusiness().getId()).stream()
                .map(membership -> UserResponse.from(membership.getUser(), membership))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id, Long actorUserId, Long activeBusinessId) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        BusinessMembership targetMembership = targetMembership(id, actorMembership.getBusiness().getId());
        return UserResponse.from(targetMembership.getUser(), targetMembership);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, Long actorUserId, String clientIp) {
        return create(request, actorUserId, null, clientIp);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, Long actorUserId, Long activeBusinessId, String clientIp) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        String requestedUsername = AuthService.normalizeUsername(request.username());
        PublicEmailIdentity usernameIdentity = PublicEmailIdentity.orNull(requestedUsername);
        String username = usernameIdentity == null ? requestedUsername : usernameIdentity.canonicalAscii();
        boolean usernameReserved = usernameIdentity == null
                ? users.findByUsername(username).isPresent()
                : users.existsByUsernameOrEmailAliasIgnoreCase(usernameIdentity.aliases());
        if (usernameReserved) {
            throw duplicateUser();
        }
        String email = normalizeEmail(request.email());
        if (email != null) {
            PublicEmailIdentity emailIdentity = PublicEmailIdentity.orNull(email);
            boolean reserved = emailIdentity == null
                    ? users.findByEmail(email).isPresent()
                    : users.existsByUsernameOrEmailAliasIgnoreCase(emailIdentity.aliases());
            if (reserved) {
                throw duplicateUser();
            }
        }

        Instant now = clock.instant();
        AppUser user = AppUser.create(
                username,
                cleanDisplayName(request.displayName()),
                passwords.encodeValidated(username, request.password()),
                request.role(),
                now);
        if (email != null) {
            user.setNormalizedEmail(email, AccountRegistrationState.PENDING_EMAIL_VERIFICATION, now);
        }
        try {
            users.saveAndFlush(user);
            BusinessMembership membership = memberships.saveAndFlush(BusinessMembership.create(
                    user, actorMembership.getBusiness(), request.role(), now));
            audit.record(
                    SecurityAuditEventType.USER_CREATED,
                    actorUserId,
                    user.getId(),
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    null);
            return UserResponse.from(user, membership);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateUser();
        }
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, Long actorUserId, String clientIp) {
        return update(id, request, actorUserId, null, clientIp);
    }

    @Transactional
    public UserResponse update(Long id,
                               UpdateUserRequest request,
                               Long actorUserId,
                               Long activeBusinessId,
                               String clientIp) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        BusinessMembership targetMembership = targetMembership(id, actorMembership.getBusiness().getId());
        AppUser user = targetMembership.getUser();
        requireExpectedVersion(user, request.version());

        String displayName = cleanDisplayName(request.displayName());
        boolean globalIdentityMutation = !displayName.equals(user.getDisplayName()) || user.isActive() != request.active();
        boolean singleBusinessIdentity = memberships.countByUserId(user.getId()) == 1;
        boolean activeChanged = user.isActive() != request.active();
        if (!singleBusinessIdentity && globalIdentityMutation) {
            throw sharedIdentityMutationForbidden();
        }

        boolean reducesActiveOwners = user.isActive()
                && targetMembership.getRole() == ApplicationRole.OWNER
                && (!request.active() || request.role() != ApplicationRole.OWNER);
        if (reducesActiveOwners) {
            assertAnotherActiveOwnerExists(actorMembership.getBusiness().getId(), user.getId());
        }

        boolean roleChanged = targetMembership.getRole() != request.role();
        targetMembership.updateRole(request.role(), clock.instant());
        if (singleBusinessIdentity) {
            // A single-business user preserves the legacy administrative behaviour and
            // keeps the compatibility snapshot synchronized with its only membership.
            user.updateLegacyAdministrativeState(displayName, request.role(), request.active(), clock.instant());
        }
        if (roleChanged || (singleBusinessIdentity && activeChanged)) {
            sessions.revokeAllForBusiness(user.getId(), actorMembership.getBusiness().getId(),
                    request.active() ? "ROLE_CHANGED" : "USER_DISABLED", actorUserId, clientIp);
        }
        audit.record(
                request.active() ? SecurityAuditEventType.USER_UPDATED : SecurityAuditEventType.USER_DISABLED,
                actorUserId,
                id,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return UserResponse.from(user, targetMembership);
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request, Long actorUserId, String clientIp) {
        resetPassword(id, request, actorUserId, null, clientIp);
    }

    @Transactional
    public void resetPassword(Long id,
                              ResetPasswordRequest request,
                              Long actorUserId,
                              Long activeBusinessId,
                              String clientIp) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        BusinessMembership targetMembership = targetMembership(id, actorMembership.getBusiness().getId());
        AppUser user = targetMembership.getUser();
        requireExpectedVersion(user, request.version());
        if (memberships.countByUserId(user.getId()) != 1) {
            throw sharedIdentityMutationForbidden();
        }
        user.changePassword(passwords.encodeValidated(user.getUsername(), request.newPassword()), clock.instant());
        sessions.revokeAllForBusiness(user.getId(), actorMembership.getBusiness().getId(),
                "PASSWORD_RESET", actorUserId, clientIp);
        audit.record(
                SecurityAuditEventType.PASSWORD_RESET,
                actorUserId,
                id,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> sessions(Long userId, UUID currentSessionId, Long actorUserId, Long activeBusinessId) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        targetMembership(userId, actorMembership.getBusiness().getId());
        return sessions.sessions(userId, actorMembership.getBusiness().getId()).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    @Transactional
    public void revokeSession(UUID sessionId, Long actorUserId, Long activeBusinessId, String clientIp) {
        BusinessMembership actorMembership = actorMembership(actorUserId, activeBusinessId);
        sessions.revokeForBusiness(sessionId, actorMembership.getBusiness().getId(), actorUserId, "OWNER_REVOKED", clientIp);
    }

    private void assertAnotherActiveOwnerExists(Long businessId, Long targetUserId) {
        List<BusinessMembership> owners = memberships.findActiveOwnersForBusinessForUpdate(businessId);
        boolean targetIsLockedOwner = owners.stream()
                .anyMatch(owner -> owner.getUser().getId().equals(targetUserId));
        if (targetIsLockedOwner && owners.size() == 1) {
            throw new SecurityApiException(
                    "INVALID_USER",
                    HttpStatus.BAD_REQUEST,
                    "Debe permanecer al menos un propietario activo.");
        }
    }

    private BusinessMembership actorMembership(Long actorUserId, Long activeBusinessId) {
        if (activeBusinessId != null) {
            return memberships.findByUserIdAndBusinessId(actorUserId, activeBusinessId)
                    .filter(membership -> membership.getBusiness().isActive())
                    .orElseThrow(UserManagementService::businessForbidden);
        }
        List<BusinessMembership> available = memberships.findByUserIdOrderByIdAsc(actorUserId).stream()
                .filter(membership -> membership.getBusiness().isActive())
                .toList();
        if (available.size() == 1) {
            return available.get(0);
        }
        throw businessForbidden();
    }

    private BusinessMembership targetMembership(Long userId, Long businessId) {
        return memberships.findByUserIdAndBusinessId(userId, businessId).orElseThrow(() -> new SecurityApiException(
                "USER_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                "El usuario no existe."));
    }

    private static void requireExpectedVersion(AppUser user, Long expectedVersion) {
        if (user.getVersion() != expectedVersion.longValue()) {
            throw new SecurityApiException(
                    "USER_VERSION_CONFLICT",
                    HttpStatus.CONFLICT,
                    "El usuario fue modificado por otra operación.");
        }
    }

    private static SecurityApiException duplicateUser() {
        return new SecurityApiException("INVALID_USER", HttpStatus.CONFLICT, "El usuario ya existe.");
    }

    private static SecurityApiException businessForbidden() {
        return new SecurityApiException(
                "AUTH_BUSINESS_FORBIDDEN", HttpStatus.FORBIDDEN, "No tienes acceso al negocio solicitado.");
    }

    private static SecurityApiException sharedIdentityMutationForbidden() {
        return new SecurityApiException(
                "USER_SHARED_IDENTITY_MUTATION_FORBIDDEN",
                HttpStatus.CONFLICT,
                "La identidad pertenece a más de un negocio y no puede modificarse desde esta administración.");
    }

    private static String cleanDisplayName(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            throw new SecurityApiException("INVALID_USER", HttpStatus.BAD_REQUEST, "El usuario no es válido.");
        }
        return cleaned;
    }

    static String normalizeEmail(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        PublicEmailIdentity identity = PublicEmailIdentity.orNull(value);
        // Preserve the existing optional-email behavior for malformed values;
        // controller validation remains responsible for rejecting those requests.
        return identity == null ? value.trim().toLowerCase(Locale.ROOT) : identity.canonicalAscii();
    }
}
