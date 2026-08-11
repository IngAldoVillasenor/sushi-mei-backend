package com.sushimei.sushimei.backend.security;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityAuditService {

    private final SecurityAuditEventRepository auditEventRepository;
    private final Clock clock;

    public SecurityAuditService(SecurityAuditEventRepository auditEventRepository, Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
    }

    /**
     * Joins the caller's transaction. Security mutations and their audit event
     * deliberately succeed or roll back together.
     */
    @Transactional
    public void record(SecurityAuditEventType eventType,
                       Long actorUserId,
                       Long subjectUserId,
                       UUID sessionId,
                       String deviceId,
                       String clientIp,
                       SecurityAuditOutcome outcome,
                       String reasonCode) {
        auditEventRepository.save(SecurityAuditEvent.create(
                eventType,
                actorUserId,
                subjectUserId,
                sessionId,
                deviceId,
                clientIp,
                outcome,
                reasonCode,
                clock.instant()));
    }
}