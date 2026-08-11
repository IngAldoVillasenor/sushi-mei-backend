package com.sushimei.sushimei.backend.security;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        String deviceId,
        String deviceName,
        String appVersion,
        Instant createdAt,
        Instant lastRefreshedAt,
        Instant absoluteExpiresAt,
        Instant revokedAt,
        String revokeReason,
        boolean current) {

    static SessionResponse from(AuthSession session, UUID currentSessionId) {
        return new SessionResponse(
                session.getId(),
                session.getDeviceId(),
                session.getDeviceName(),
                session.getAppVersion(),
                session.getCreatedAt(),
                session.getLastRefreshedAt(),
                session.getAbsoluteExpiresAt(),
                session.getRevokedAt(),
                session.getRevokeReason(),
                session.getId().equals(currentSessionId));
    }
}