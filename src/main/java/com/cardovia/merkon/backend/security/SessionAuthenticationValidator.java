package com.cardovia.merkon.backend.security;

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
    public boolean valid(String subject,
                         String sessionIdClaim,
                         String roleClaim,
                         String usernameClaim,
                         String businessIdClaim,
                         String membershipIdClaim) {
        try {
            Long userId = Long.valueOf(subject);
            UUID sessionId = UUID.fromString(sessionIdClaim);
            ApplicationRole role = ApplicationRole.valueOf(roleClaim);
            AuthSession session = sessions.findById(sessionId).orElse(null);
            if (session == null || session.isRevoked() || !clock.instant().isBefore(session.getAbsoluteExpiresAt())) {
                return false;
            }
            AppUser user = session.getUser();
            if (!user.getId().equals(userId) || !user.isActive() || !user.getUsername().equals(usernameClaim)) {
                return false;
            }
            var membership = session.getActiveMembership();
            if (!membership.getUser().getId().equals(userId) || !membership.getBusiness().isActive()) {
                return false;
            }
            if (businessIdClaim == null || membershipIdClaim == null) {
                // A short-lived token issued before V27 can recover through its preserved refresh
                // session only while its role still agrees with the authoritative membership.
                return membership.getRole() == role;
            }
            return membership.getId().equals(Long.valueOf(membershipIdClaim))
                    && membership.getBusiness().getId().equals(Long.valueOf(businessIdClaim))
                    && membership.getRole() == role;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
