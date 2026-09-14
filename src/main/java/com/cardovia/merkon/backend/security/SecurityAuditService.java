package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
        save(eventType, actorUserId, subjectUserId, sessionId, deviceId, clientIp, outcome, reasonCode);
    }

    /**
     * Persists safe evidence raised after a different transaction has already
     * committed (for example, best-effort filesystem cleanup). It must never
     * be attached to that completed transaction's persistence context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPostCommit(SecurityAuditEventType eventType,
                                 Long actorUserId,
                                 Long subjectUserId,
                                 UUID sessionId,
                                 String deviceId,
                                 String clientIp,
                                 SecurityAuditOutcome outcome,
                                 String reasonCode) {
        save(eventType, actorUserId, subjectUserId, sessionId, deviceId, clientIp, outcome, reasonCode);
    }

    private void save(SecurityAuditEventType eventType,
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
