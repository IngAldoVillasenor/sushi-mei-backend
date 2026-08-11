package com.sushimei.sushimei.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_audit_events")
public class SecurityAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private SecurityAuditEventType eventType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "subject_user_id")
    private Long subjectUserId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "device_id", length = 120)
    private String deviceId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SecurityAuditOutcome outcome;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SecurityAuditEvent() {
    }

    static SecurityAuditEvent create(SecurityAuditEventType eventType,
                                     Long actorUserId,
                                     Long subjectUserId,
                                     UUID sessionId,
                                     String deviceId,
                                     String clientIp,
                                     SecurityAuditOutcome outcome,
                                     String reasonCode,
                                     Instant occurredAt) {
        SecurityAuditEvent event = new SecurityAuditEvent();
        event.eventType = eventType;
        event.actorUserId = actorUserId;
        event.subjectUserId = subjectUserId;
        event.sessionId = sessionId;
        event.deviceId = deviceId;
        event.clientIp = clientIp;
        event.outcome = outcome;
        event.reasonCode = reasonCode;
        event.occurredAt = occurredAt;
        return event;
    }
}