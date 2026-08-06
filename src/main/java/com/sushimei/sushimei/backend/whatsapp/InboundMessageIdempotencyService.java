package com.sushimei.sushimei.backend.whatsapp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Owns short database transactions for WhatsApp inbound-message claims and outcomes.
 * External processing deliberately happens after {@link #claim(String, String, String)} returns.
 */
@Service
public class InboundMessageIdempotencyService {

    private static final String POSTGRES_CLAIM_SQL = """
            INSERT INTO public.whatsapp_inbound_messages
                (message_id, phone_number, message_type, processing_status, received_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
            """;

    private static final String H2_CLAIM_SQL = """
            INSERT INTO public.whatsapp_inbound_messages
                (message_id, phone_number, message_type, processing_status, received_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public InboundMessageIdempotencyService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InboundMessageClaimOutcome claim(String messageId, String phoneNumber, String messageType) {
        String validatedMessageId = requireNonBlank(messageId, "messageId");
        String validatedPhoneNumber = requireNonBlank(phoneNumber, "phoneNumber");
        String validatedMessageType = requireNonBlank(messageType, "messageType");
        Timestamp receivedAt = Timestamp.from(clock.instant());

        if (isPostgreSql()) {
            int inserted = jdbcTemplate.update(
                    POSTGRES_CLAIM_SQL,
                    validatedMessageId,
                    validatedPhoneNumber,
                    validatedMessageType,
                    InboundMessageProcessingStatus.PROCESSING.name(),
                    receivedAt
            );
            return inserted == 1 ? InboundMessageClaimOutcome.NEW : InboundMessageClaimOutcome.DUPLICATE;
        }

        try {
            jdbcTemplate.update(
                    H2_CLAIM_SQL,
                    validatedMessageId,
                    validatedPhoneNumber,
                    validatedMessageType,
                    InboundMessageProcessingStatus.PROCESSING.name(),
                    receivedAt
            );
            return InboundMessageClaimOutcome.NEW;
        } catch (DuplicateKeyException exception) {
            return InboundMessageClaimOutcome.DUPLICATE;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String messageId) {
        updateOutcome(requireNonBlank(messageId, "messageId"), InboundMessageProcessingStatus.COMPLETED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String messageId) {
        updateOutcome(requireNonBlank(messageId, "messageId"), InboundMessageProcessingStatus.FAILED);
    }

    private void updateOutcome(String messageId, InboundMessageProcessingStatus outcome) {
        Instant now = clock.instant();
        String timestampColumn = outcome == InboundMessageProcessingStatus.COMPLETED ? "completed_at" : "failed_at";
        jdbcTemplate.update("""
                        UPDATE public.whatsapp_inbound_messages
                        SET processing_status = ?, %s = ?
                        WHERE message_id = ? AND processing_status = ?
                        """.formatted(timestampColumn),
                outcome.name(),
                Timestamp.from(now),
                messageId,
                InboundMessageProcessingStatus.PROCESSING.name());
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")
        ));
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
