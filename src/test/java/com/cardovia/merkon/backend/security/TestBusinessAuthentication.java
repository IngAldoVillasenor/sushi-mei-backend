package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Creates a persisted test session so controller tests exercise the production session validator. */
public final class TestBusinessAuthentication {

    private TestBusinessAuthentication() {
    }

    public static JwtRequestPostProcessor owner(JdbcTemplate jdbcTemplate, LegacyBusinessResolver legacyBusinessResolver) {
        return authenticated(jdbcTemplate, legacyBusinessResolver, ApplicationRole.OWNER);
    }

    public static JwtRequestPostProcessor authenticated(JdbcTemplate jdbcTemplate,
                                                         LegacyBusinessResolver legacyBusinessResolver,
                                                         ApplicationRole role) {
        return authenticated(jdbcTemplate, legacyBusinessResolver.requireLegacyBusinessId(), role);
    }

    public static JwtRequestPostProcessor authenticated(JdbcTemplate jdbcTemplate,
                                                         Long businessId,
                                                         ApplicationRole role) {
        String username = "test-owner-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, '{bcrypt}not-used', ?, true, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """, username, username, role.name());
        Long userId = jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
        jdbcTemplate.update("""
                insert into public.business_memberships (user_id, business_id, role, created_at, updated_at, version)
                values (?, ?, ?, current_timestamp, current_timestamp, 0)
                """, userId, businessId, role.name());
        Long membershipId = jdbcTemplate.queryForObject(
                "select id from public.business_memberships where user_id = ? and business_id = ?", Long.class, userId, businessId);
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into public.auth_sessions (id, user_id, active_membership_id, device_id, current_refresh_token_hash, created_at,
                    last_refreshed_at, absolute_expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, userId, membershipId, "test-device-" + username,
                UUID.randomUUID().toString().replace("-", "").repeat(2), now, now, now.plusSeconds(900));
        Jwt jwt = new Jwt("test-token", now, now.plusSeconds(900), Map.of("alg", "none"), Map.of(
                "sub", userId.toString(), "sid", sessionId.toString(), "mid", membershipId.toString(),
                "bid", businessId.toString(), "role", role.name(), "username", username));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt)
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
