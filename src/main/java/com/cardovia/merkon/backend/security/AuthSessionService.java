package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {

    private final AppUserRepository users;
    private final BusinessMembershipRepository memberships;
    private final AuthSessionRepository sessions;
    private final RefreshTokenHistoryRepository refreshHistory;
    private final RefreshTokenService refreshTokens;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final MerkonSecurityProperties properties;

    public AuthSessionService(AppUserRepository users,
                              BusinessMembershipRepository memberships,
                              AuthSessionRepository sessions,
                              RefreshTokenHistoryRepository refreshHistory,
                              RefreshTokenService refreshTokens,
                              SecurityAuditService audit,
                              Clock clock,
                              MerkonSecurityProperties properties) {
        this.users = users;
        this.memberships = memberships;
        this.sessions = sessions;
        this.refreshHistory = refreshHistory;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public SessionToken open(Long userId,
                             String passwordHashSnapshot,
                             String deviceId,
                             String deviceName,
                             String appVersion,
                             Long requestedBusinessId,
                             String clientIp) {
        Instant now = clock.instant();
        // Login validation is committed separately so its lockout/audit state
        // survives a later session-creation failure. Reacquire the user row
        // lock here and prove the password credential has not changed since
        // validation before a new session is allowed to exist.
        AppUser user = users.findByIdForUpdate(userId).orElseThrow(AuthSessionService::invalidCredentials);
        if (passwordHashSnapshot == null
                || !sameHash(user.getPasswordHash(), passwordHashSnapshot)
                || !user.isActive()
                || user.getRegistrationState() == AccountRegistrationState.PENDING_EMAIL_VERIFICATION) {
            throw invalidCredentials();
        }
        BusinessMembership activeMembership = resolveActiveMembership(user, requestedBusinessId);
        for (AuthSession existing : sessions.findActiveByUserAndDevice(userId, deviceId)) {
            existing.revoke("REPLACED_BY_LOGIN", now);
            audit.record(
                    SecurityAuditEventType.SESSION_REVOKED,
                    user.getId(),
                    user.getId(),
                    existing.getId(),
                    deviceId,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    "REPLACED_BY_LOGIN");
        }

        UUID sessionId = UUID.randomUUID();
        String rawRefreshToken = refreshTokens.issue(sessionId);
        AuthSession session = AuthSession.create(
                sessionId,
                user,
                activeMembership,
                deviceId,
                deviceName,
                appVersion,
                refreshTokens.hash(rawRefreshToken),
                now,
                now.plus(properties.sessionTtl()));
        sessions.save(session);
        return new SessionToken(user, session, rawRefreshToken);
    }

    /**
     * Serializes refresh rotation on the exact session row. The returned result
     * lets AuthService render a public error only after this transaction has
     * committed replay revocation and the corresponding audit event together.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshEvaluation rotate(String rawRefreshToken, String deviceId, String clientIp) {
        RefreshTokenService.Parsed presented = refreshTokens.parse(rawRefreshToken);
        if (presented == null) {
            audit.record(
                    SecurityAuditEventType.REFRESH_FAILURE,
                    null,
                    null,
                    null,
                    deviceId,
                    clientIp,
                    SecurityAuditOutcome.FAILURE,
                    "INVALID_FORMAT");
            return RefreshEvaluation.invalid("AUTH_REFRESH_INVALID");
        }

        Instant now = clock.instant();
        AuthSession session = sessions.findByIdForUpdate(presented.sessionId()).orElse(null);
        if (session == null) {
            audit.record(
                    SecurityAuditEventType.REFRESH_FAILURE,
                    null,
                    null,
                    presented.sessionId(),
                    deviceId,
                    clientIp,
                    SecurityAuditOutcome.FAILURE,
                    "UNKNOWN_SESSION");
            return RefreshEvaluation.invalid("AUTH_REFRESH_INVALID");
        }

        AppUser user = session.getUser();
        if (session.isRevoked()) {
            auditRefreshFailure(user, session, deviceId, clientIp, "REVOKED");
            return RefreshEvaluation.invalid("AUTH_SESSION_REVOKED");
        }
        if (!now.isBefore(session.getAbsoluteExpiresAt())) {
            session.revoke("ABSOLUTE_EXPIRY", now);
            auditRefreshFailure(user, session, deviceId, clientIp, "EXPIRED");
            return RefreshEvaluation.invalid("AUTH_SESSION_EXPIRED");
        }
        BusinessMembership activeMembership = session.getActiveMembership();
        if (!user.isActive()
                || !activeMembership.getUser().getId().equals(user.getId())
                || !activeMembership.getBusiness().isActive()
                || !session.getDeviceId().equals(deviceId)) {
            if (!activeMembership.getBusiness().isActive()) {
                session.revoke("BUSINESS_INACTIVE", now);
            }
            auditRefreshFailure(user, session, deviceId, clientIp, "INVALID_SESSION");
            return RefreshEvaluation.invalid("AUTH_REFRESH_INVALID");
        }

        if (sameHash(presented.hash(), session.getCurrentRefreshTokenHash())) {
            refreshHistory.save(RefreshTokenHistory.create(session, session.getCurrentRefreshTokenHash(), now));
            String nextRawToken = refreshTokens.issue(session.getId());
            session.rotate(refreshTokens.hash(nextRawToken), now);
            audit.record(
                    SecurityAuditEventType.REFRESH_SUCCESS,
                    user.getId(),
                    user.getId(),
                    session.getId(),
                    deviceId,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    null);
            return RefreshEvaluation.success(new SessionToken(user, session, nextRawToken));
        }

        if (refreshHistory.existsByTokenHash(presented.hash())) {
            session.revoke("REFRESH_REPLAY_DETECTED", now);
            audit.record(
                    SecurityAuditEventType.REFRESH_REPLAY_DETECTED,
                    user.getId(),
                    user.getId(),
                    session.getId(),
                    deviceId,
                    clientIp,
                    SecurityAuditOutcome.FAILURE,
                    "REFRESH_REPLAY_DETECTED");
            return RefreshEvaluation.invalid("AUTH_REFRESH_REPLAY_DETECTED");
        }

        auditRefreshFailure(user, session, deviceId, clientIp, "UNKNOWN_TOKEN");
        return RefreshEvaluation.invalid("AUTH_REFRESH_INVALID");
    }

    @Transactional
    public void revoke(UUID sessionId, Long actorUserId, String reason, String clientIp) {
        AuthSession session = sessions.findById(sessionId).orElseThrow(() -> new SecurityApiException(
                "AUTH_SESSION_REVOKED",
                HttpStatus.NOT_FOUND,
                "La sesión no existe."));
        session.revoke(reason, clock.instant());
        audit.record(
                SecurityAuditEventType.SESSION_REVOKED,
                actorUserId,
                session.getUser().getId(),
                sessionId,
                session.getDeviceId(),
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                reason);
    }

    @Transactional(readOnly = true)
    public List<AuthSession> sessions(Long userId) {
        return sessions.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<AuthSession> sessions(Long userId, Long businessId) {
        return sessions.findByUserIdAndBusinessIdOrderByCreatedAtDesc(userId, businessId);
    }

    @Transactional(readOnly = true)
    public AuthSession findById(UUID sessionId) {
        return sessions.findByIdWithContext(sessionId).orElseThrow(() -> new SecurityApiException(
                "AUTH_SESSION_REVOKED",
                HttpStatus.NOT_FOUND,
                "La sesión no existe."));
    }

    @Transactional
    public void revokeAll(Long userId, String reason, Long actorUserId, String clientIp) {
        for (AuthSession session : sessions.findByUserIdAndRevokedAtIsNull(userId)) {
            revoke(session.getId(), actorUserId, reason, clientIp);
        }
    }

    @Transactional
    public void revokeAllForBusiness(Long userId, Long businessId, String reason, Long actorUserId, String clientIp) {
        for (AuthSession session : sessions.findActiveByUserIdAndBusinessId(userId, businessId)) {
            revoke(session.getId(), actorUserId, reason, clientIp);
        }
    }

    @Transactional
    public void revokeForBusiness(UUID sessionId, Long businessId, Long actorUserId, String reason, String clientIp) {
        AuthSession session = sessions.findByIdWithContext(sessionId).orElseThrow(() -> new SecurityApiException(
                "AUTH_SESSION_REVOKED",
                HttpStatus.NOT_FOUND,
                "La sesión no existe."));
        if (!session.getActiveMembership().getBusiness().getId().equals(businessId)) {
            throw new SecurityApiException(
                    "AUTH_SESSION_REVOKED",
                    HttpStatus.NOT_FOUND,
                    "La sesión no existe.");
        }
        session.revoke(reason, clock.instant());
        audit.record(
                SecurityAuditEventType.SESSION_REVOKED,
                actorUserId,
                session.getUser().getId(),
                sessionId,
                session.getDeviceId(),
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                reason);
    }

    private void auditRefreshFailure(AppUser user,
                                     AuthSession session,
                                     String deviceId,
                                     String clientIp,
                                     String reasonCode) {
        audit.record(
                SecurityAuditEventType.REFRESH_FAILURE,
                user.getId(),
                user.getId(),
                session.getId(),
                deviceId,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }

    private BusinessMembership resolveActiveMembership(AppUser user, Long requestedBusinessId) {
        if (requestedBusinessId != null) {
            return memberships.findByUserIdAndBusinessId(user.getId(), requestedBusinessId)
                    .filter(membership -> membership.getBusiness().isActive())
                    .orElseThrow(() -> new SecurityApiException(
                            "AUTH_BUSINESS_FORBIDDEN",
                            HttpStatus.FORBIDDEN,
                            "No tienes acceso al negocio solicitado."));
        }

        List<BusinessMembership> available = memberships.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(membership -> membership.getBusiness().isActive())
                .toList();
        if (available.size() == 1) {
            return available.get(0);
        }
        if (available.size() > 1) {
            throw new SecurityApiException(
                    "AUTH_ACTIVE_BUSINESS_SELECTION_REQUIRED",
                    HttpStatus.CONFLICT,
                    "Debes seleccionar un negocio activo.");
        }

        throw new SecurityApiException(
                "AUTH_BUSINESS_FORBIDDEN",
                HttpStatus.FORBIDDEN,
                "No hay un negocio activo disponible para esta sesión.");
    }

    private static boolean sameHash(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static SecurityApiException invalidCredentials() {
        return new SecurityApiException(
                "AUTH_INVALID_CREDENTIALS",
                HttpStatus.UNAUTHORIZED,
                "Usuario o contrase\u00f1a incorrectos.");
    }

    public record SessionToken(AppUser user, AuthSession session, String rawRefreshToken) {
    }

    public record RefreshEvaluation(SessionToken token, String error) {
        static RefreshEvaluation success(SessionToken token) {
            return new RefreshEvaluation(token, null);
        }

        static RefreshEvaluation invalid(String error) {
            return new RefreshEvaluation(null, error);
        }
    }
}
