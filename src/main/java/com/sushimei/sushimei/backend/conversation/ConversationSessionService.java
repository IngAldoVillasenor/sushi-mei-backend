package com.sushimei.sushimei.backend.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Persists shadow conversation data only. It does not decide checkout state or responses.
 */
@Service
public class ConversationSessionService {

    private final ConversationSessionRepository conversationSessionRepository;
    private final Clock clock;

    public ConversationSessionService(ConversationSessionRepository conversationSessionRepository, Clock clock) {
        this.conversationSessionRepository = conversationSessionRepository;
        this.clock = clock;
    }

    @Transactional
    public ConversationSession getOrCreateSession(String phoneNumber) {
        String persistedPhoneNumber = validatedPhoneNumber(phoneNumber);
        return getOrCreateSession(persistedPhoneNumber, clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<ConversationSession> findSession(String phoneNumber) {
        return conversationSessionRepository.findById(validatedPhoneNumber(phoneNumber));
    }

    @Transactional
    public ConversationSession recordInboundActivity(String phoneNumber) {
        String persistedPhoneNumber = validatedPhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = getOrCreateSession(persistedPhoneNumber, now);
        session.recordActivity(now);
        return session;
    }

    @Transactional
    public ConversationSession recordTransferReceipt(String phoneNumber, String receiptPath) {
        if (receiptPath == null || receiptPath.isBlank()) {
            throw new IllegalArgumentException("receiptPath must not be blank");
        }

        String persistedPhoneNumber = validatedPhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = getOrCreateSession(persistedPhoneNumber, now);
        session.recordTransferReceipt(receiptPath, now);
        return session;
    }

    @Transactional
    public ConversationSession resetSession(String phoneNumber) {
        String persistedPhoneNumber = validatedPhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = getOrCreateSession(persistedPhoneNumber, now);
        session.reset(now);
        return session;
    }

    private ConversationSession getOrCreateSession(String phoneNumber, Instant now) {
        return conversationSessionRepository.findById(phoneNumber)
                .orElseGet(() -> conversationSessionRepository.save(ConversationSession.create(phoneNumber, now)));
    }

    private String validatedPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber must not be blank");
        }
        return phoneNumber.trim();
    }
}
