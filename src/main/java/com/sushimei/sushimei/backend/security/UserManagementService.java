package com.sushimei.sushimei.backend.security;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {

    private final AppUserRepository users;
    private final PasswordPolicyService passwords;
    private final AuthSessionService sessions;
    private final SecurityAuditService audit;
    private final Clock clock;

    public UserManagementService(AppUserRepository users,
                                 PasswordPolicyService passwords,
                                 AuthSessionService sessions,
                                 SecurityAuditService audit,
                                 Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return UserResponse.from(user(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, Long actorUserId, String clientIp) {
        String username = AuthService.normalizeUsername(request.username());
        if (users.findByUsername(username).isPresent()) {
            throw duplicateUser();
        }

        AppUser user = AppUser.create(
                username,
                cleanDisplayName(request.displayName()),
                passwords.encodeValidated(username, request.password()),
                request.role(),
                clock.instant());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateUser();
        }
        audit.record(
                SecurityAuditEventType.USER_CREATED,
                actorUserId,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, Long actorUserId, String clientIp) {
        AppUser user = user(id);
        requireExpectedVersion(user, request.version());

        boolean reducesActiveOwners = user.isActive()
                && user.getRole() == ApplicationRole.OWNER
                && (!request.active() || request.role() != ApplicationRole.OWNER);
        if (reducesActiveOwners) {
            assertAnotherActiveOwnerExists(id);
        }

        boolean revokeSessions = user.getRole() != request.role() || user.isActive() != request.active();
        user.update(cleanDisplayName(request.displayName()), request.role(), request.active(), clock.instant());
        if (revokeSessions) {
            sessions.revokeAll(id, request.active() ? "ROLE_CHANGED" : "USER_DISABLED", actorUserId, clientIp);
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
        return UserResponse.from(user);
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request, Long actorUserId, String clientIp) {
        AppUser user = user(id);
        requireExpectedVersion(user, request.version());
        user.changePassword(passwords.encodeValidated(user.getUsername(), request.newPassword()), clock.instant());
        sessions.revokeAll(id, "PASSWORD_RESET", actorUserId, clientIp);
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
    public List<SessionResponse> sessions(Long userId, UUID currentSessionId) {
        user(userId);
        return sessions.sessions(userId).stream().map(session -> SessionResponse.from(session, currentSessionId)).toList();
    }

    public void revokeSession(UUID sessionId, Long actorUserId, String clientIp) {
        sessions.revoke(sessionId, actorUserId, "OWNER_REVOKED", clientIp);
    }

    private void assertAnotherActiveOwnerExists(Long targetUserId) {
        List<AppUser> owners = users.findActiveOwnersForUpdate();
        boolean targetIsLockedOwner = owners.stream().anyMatch(owner -> owner.getId().equals(targetUserId));
        if (targetIsLockedOwner && owners.size() == 1) {
            throw new SecurityApiException(
                    "INVALID_USER",
                    HttpStatus.BAD_REQUEST,
                    "Debe permanecer al menos un propietario activo.");
        }
    }

    private AppUser user(Long id) {
        return users.findById(id).orElseThrow(() -> new SecurityApiException(
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

    private static String cleanDisplayName(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            throw new SecurityApiException("INVALID_USER", HttpStatus.BAD_REQUEST, "El usuario no es válido.");
        }
        return cleaned;
    }
}