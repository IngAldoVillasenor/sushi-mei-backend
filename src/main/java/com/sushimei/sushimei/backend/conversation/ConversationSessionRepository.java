package com.sushimei.sushimei.backend.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {
}
