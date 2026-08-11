package com.sushimei.sushimei.backend.security;

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
    private final AuthSessionRepository sessions;
    private final RefreshTokenHistoryRepository refreshHistory;
    private final RefreshTokenService refreshTokens;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final SushiMeiSecurityProperties properties;

    public AuthSessionService(AppUserRepository users,
                              AuthSessionRepository sessions,
                              RefreshTokenHistoryRepository refreshHistory,
                              RefreshTokenService refreshTokens,
                              SecurityAuditService audit,
                              Clock clock,
                              SushiMeiSecurityProperties properties) {
        this.users = users;
        this.sessions = sessions;
        this.refreshHistory = refreshHistory;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public SessionToken open(Long userId,
                             String deviceId,
                             String deviceName,
                             String appVersion,
                             String clientIp) {
        Instant now = clock.instant();
        AppUser user = users.findById(userId).orElseThrow();
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
        if (!user.isActive() || !session.getDeviceId().equals(deviceId)) {
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
    public AuthSession findById(UUID sessionId) {
        return sessions.findById(sessionId).orElseThrow(() -> new SecurityApiException(
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

    private static boolean sameHash(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
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