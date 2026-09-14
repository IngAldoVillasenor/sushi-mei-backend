package com.cardovia.merkon.backend.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Purpose-specific physical cleanup for destructive flows. It deliberately
 * does not load or mutate AuthSession entities before issuing bulk deletes.
 */
@Component
class DestructiveSessionCleanupService {
    private final JdbcTemplate jdbc;

    DestructiveSessionCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void deleteForUser(Long userId) {
        delete("select id from auth_sessions where user_id = ?", userId);
    }

    void deleteForBusiness(Long businessId) {
        delete("select s.id from auth_sessions s join business_memberships m on m.id = s.active_membership_id where m.business_id = ?", businessId);
    }

    private void delete(String sessionIdSql, Long parameter) {
        var ids = jdbc.query(sessionIdSql, (rs, row) -> UUID.fromString(rs.getString(1)), parameter);
        if (ids.isEmpty()) return;
        jdbc.batchUpdate("delete from auth_refresh_token_history where session_id = ?",
                ids, ids.size(), (statement, id) -> statement.setObject(1, id));
        jdbc.batchUpdate("delete from auth_sessions where id = ?",
                ids, ids.size(), (statement, id) -> statement.setObject(1, id));
    }
}
