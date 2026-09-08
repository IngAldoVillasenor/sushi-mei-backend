package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Starts from the actual V26 migration state, then lets the application apply
 * V27 and rotate an already-issued legacy refresh token through its real flow.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, V26SessionRefreshContinuityIntegrationTest.TestInfrastructureConfiguration.class})
class V26SessionRefreshContinuityIntegrationTest {

    private static final String DATABASE_URL = "jdbc:h2:mem:v26_session_continuity_" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final JdbcConnectionPool DATA_SOURCE = JdbcConnectionPool.create(DATABASE_URL, "sa", "");
    private static final UUID LEGACY_SESSION_ID = UUID.randomUUID();
    private static final String LEGACY_REFRESH = "smr_" + LEGACY_SESSION_ID + ".legacy-refresh-secret";
    private static final RefreshTokenService REFRESH_TOKENS = new RefreshTokenService();

    static {
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration/h2")
                .defaultSchema("PUBLIC")
                .schemas("PUBLIC")
                .target(MigrationVersion.fromVersion("26"))
                .load()
                .migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(DATA_SOURCE);
        Instant now = Instant.parse("2026-09-08T12:00:00Z");
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active,
                    failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('v26-refresh-user', 'V26 Refresh User', '{bcrypt}hash', 'MANAGER', true,
                    0, ?, ?, ?, 0)
                """, now, now, now);
        Long userId = jdbcTemplate.queryForObject(
                "select id from public.app_users where username = 'v26-refresh-user'", Long.class);
        jdbcTemplate.update("""
                insert into public.auth_sessions (id, user_id, device_id, device_name, app_version,
                    current_refresh_token_hash, created_at, last_refreshed_at, absolute_expires_at)
                values (?, ?, 'v26-device', 'V26 device', '1.0', ?, ?, ?, ?)
                """, LEGACY_SESSION_ID, userId, REFRESH_TOKENS.hash(LEGACY_REFRESH), now, now, now.plusSeconds(86_400));
    }

    @Autowired private AuthService authService;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BusinessMembershipRepository memberships;

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void disposePreparedDataSource() {
        DATA_SOURCE.dispose();
    }

    @Test
    void v26SessionIsBackfilledAndItsLegacyRefreshTokenRotatesToMerkonClaims() {
        Long userId = jdbcTemplate.queryForObject(
                "select id from public.app_users where username = 'v26-refresh-user'", Long.class);
        Long membershipId = jdbcTemplate.queryForObject(
                "select active_membership_id from public.auth_sessions where id = ?", Long.class, LEGACY_SESSION_ID);
        var membership = memberships.findById(membershipId).orElseThrow();

        AuthResponse response = authService.refresh(new RefreshRequest(LEGACY_REFRESH, "v26-device"), "127.0.0.1");
        Jwt jwt = jwtDecoder.decode(response.accessToken());

        assertThat(response.refreshToken()).startsWith("mkr_");
        assertThat(jwt.getClaimAsString("mid")).isEqualTo(membershipId.toString());
        assertThat(jwt.getClaimAsString("bid")).isEqualTo(membership.getBusiness().getId().toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo(membership.getRole().name());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.auth_sessions session
                join public.business_memberships membership on membership.id = session.active_membership_id
                where session.id = ? and session.user_id = membership.user_id and session.user_id = ?
                """, Integer.class, LEGACY_SESSION_ID, userId)).isOne();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
