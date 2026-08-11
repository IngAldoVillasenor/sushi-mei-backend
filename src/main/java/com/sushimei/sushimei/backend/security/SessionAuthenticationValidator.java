package com.sushimei.sushimei.backend.security;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionAuthenticationValidator {

    private final AuthSessionRepository sessions;
    private final Clock clock;

    public SessionAuthenticationValidator(AuthSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean valid(String subject, String sessionIdClaim, String roleClaim, String usernameClaim) {
        try {
            Long userId = Long.valueOf(subject);
            UUID sessionId = UUID.fromString(sessionIdClaim);
            ApplicationRole role = ApplicationRole.valueOf(roleClaim);
            AuthSession session = sessions.findById(sessionId).orElse(null);
            if (session == null || session.isRevoked() || !clock.instant().isBefore(session.getAbsoluteExpiresAt())) {
                return false;
            }
            AppUser user = session.getUser();
            return user.getId().equals(userId)
                    && user.isActive()
                    && user.getRole() == role
                    && user.getUsername().equals(usernameClaim);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}