package com.sushimei.sushimei.backend.security;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final LoginAttemptService loginAttempts;
    private final AuthSessionService sessions;
    private final JwtService jwtService;
    private final AppUserRepository users;
    private final PasswordPolicyService passwords;
    private final SecurityAuditService audit;
    private final Clock clock;

    public AuthService(LoginAttemptService loginAttempts,
                       AuthSessionService sessions,
                       JwtService jwtService,
                       AppUserRepository users,
                       PasswordPolicyService passwords,
                       SecurityAuditService audit,
                       Clock clock) {
        this.loginAttempts = loginAttempts;
        this.sessions = sessions;
        this.jwtService = jwtService;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    public AuthResponse login(LoginRequest request, String clientIp) {
        String username = normalizeUsername(request.username());
        LoginAttemptService.LoginEvaluation evaluation = loginAttempts.evaluate(username, request.password(), clientIp);
        if (!evaluation.success()) {
            throw invalidCredentials();
        }
        return response(sessions.open(
                evaluation.userId(),
                requiredTrim(request.deviceId()),
                nullableTrim(request.deviceName()),
                nullableTrim(request.appVersion()),
                clientIp));
    }

    public AuthResponse refresh(RefreshRequest request, String clientIp) {
        AuthSessionService.RefreshEvaluation evaluation = sessions.rotate(
                request.refreshToken(),
                requiredTrim(request.deviceId()),
                clientIp);
        if (evaluation.error() != null) {
            throw refreshError(evaluation.error());
        }
        return response(evaluation.token());
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return UserResponse.from(users.findById(userId).orElseThrow(() -> unauthorized()));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request, String clientIp) {
        AppUser user = users.findById(userId).orElseThrow(AuthService::unauthorized);
        if (!passwords.matches(request.currentPassword(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        user.changePassword(passwords.encodeValidated(user.getUsername(), request.newPassword()), clock.instant());
        sessions.revokeAll(userId, "PASSWORD_CHANGED", userId, clientIp);
        audit.record(
                SecurityAuditEventType.PASSWORD_CHANGED,
                userId,
                userId,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
    }

    public List<SessionResponse> ownSessions(Long userId, UUID currentSessionId) {
        return sessions.sessions(userId).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    @Transactional
    public void revokeOwn(Long userId, UUID sessionId, String clientIp) {
        AuthSession session = sessions.findById(sessionId);
        if (!session.getUser().getId().equals(userId)) {
            throw new SecurityApiException(
                    "AUTH_FORBIDDEN",
                    HttpStatus.FORBIDDEN,
                    "No tienes permisos para realizar esta operación.");
        }
        sessions.revoke(sessionId, userId, "USER_REVOKED", clientIp);
    }

    static String normalizeUsername(String username) {
        return requiredTrim(username).toLowerCase(Locale.ROOT);
    }

    private AuthResponse response(AuthSessionService.SessionToken token) {
        JwtService.Issued accessToken = jwtService.issue(token.user(), token.session(), clock.instant());
        return new AuthResponse(
                accessToken.value(),
                accessToken.expiresAt(),
                token.rawRefreshToken(),
                token.session().getAbsoluteExpiresAt(),
                UserResponse.from(token.user()));
    }

    private static String requiredTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw invalidCredentials();
        }
        return value.trim();
    }

    private static String nullableTrim(String value) {
        return value == null ? null : value.trim();
    }

    private static SecurityApiException invalidCredentials() {
        return new SecurityApiException(
                "AUTH_INVALID_CREDENTIALS",
                HttpStatus.UNAUTHORIZED,
                "Usuario o contraseña incorrectos.");
    }

    private static SecurityApiException unauthorized() {
        return new SecurityApiException("AUTH_UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Autenticación requerida.");
    }

    private static SecurityApiException refreshError(String code) {
        return new SecurityApiException(
                code,
                HttpStatus.UNAUTHORIZED,
                "La sesión de actualización no es válida.");
    }
}