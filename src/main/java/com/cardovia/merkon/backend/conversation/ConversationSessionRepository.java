package com.cardovia.merkon.backend.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {
    Optional<ConversationSession> findByPhoneNumberAndBusinessId(String phoneNumber, Long businessId);
}
