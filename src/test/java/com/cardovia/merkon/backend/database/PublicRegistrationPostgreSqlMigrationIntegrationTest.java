package com.cardovia.merkon.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;

/** Exercises the V30 upgrade path through both the V31 and V32 production schemas. */
@Testcontainers(disabledWithoutDocker = true)
class PublicRegistrationPostgreSqlMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("merkon_registration_migration")
            .withUsername("merkon_registration")
            .withPassword("merkon_registration_password");

    @Test
    void v30UpgradePreservesExistingUsersAndAddsV31AndV32Constraints() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        flyway(dataSource, MigrationVersion.fromVersion("30")).migrate();
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active,
                    failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('legacy-v31-postgres', 'Legacy V31 PostgreSQL', '{bcrypt}hash', 'MANAGER', true,
                    0, current_timestamp, current_timestamp, current_timestamp, 0)
                """);
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, email, registration_state, password_hash, role,
                    active, failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('pending-v31-postgres@example.com', 'Pending V31 PostgreSQL', 'pending-v31-postgres@example.com',
                    'PENDING_EMAIL_VERIFICATION', '{bcrypt}hash', 'OWNER', false, 0,
                    current_timestamp, current_timestamp, current_timestamp, 0)
                """);

        flyway(dataSource, null).migrate();

        assertThat(jdbcTemplate.queryForObject("""
                select "version" from public.flyway_schema_history
                where success and "version" is not null
                order by installed_rank desc limit 1
                """, String.class)).isEqualTo("32");
        assertThat(jdbcTemplate.queryForObject("""
                select username from public.app_users where username = 'legacy-v31-postgres'
                """, String.class)).isEqualTo("legacy-v31-postgres");
        assertThat(jdbcTemplate.queryForObject("""
                select registration_state from public.app_users where username = 'pending-v31-postgres@example.com'
                """, String.class)).isEqualTo("PENDING_EMAIL_VERIFICATION");
        assertThat(jdbcTemplate.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_schema = 'public' and table_name = 'app_users' and column_name = 'username'
                """, Integer.class)).isEqualTo(254);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'public.user_terms_acceptances'::regclass
                  and conname = 'user_terms_acceptances_user_version_key'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'registration_rate_limit_buckets'
                  and column_name = 'bucket_key'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'email_verification_tokens'
                  and column_name = 'token_hash'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'password_reset_tokens'
                  and column_name = 'token_hash'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'account_deletion_requests'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.flyway_schema_history
                where success and version = '32'
                """, Integer.class)).isOne();
        for (String column : java.util.List.of("id", "user_id", "source", "status", "token_hash", "requested_at",
                "expires_at", "confirmed_at", "completed_at", "action_code")) {
            assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema = 'public' and table_name = 'account_deletion_requests' and column_name = ?
                    """, Integer.class, column)).isOne();
        }
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'public.account_deletion_requests'::regclass
                  and conname = 'account_deletion_requests_token_hash_key'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'public.account_deletion_requests'::regclass
                  and conname = 'account_deletion_requests_user_id_fkey'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'public.account_deletion_requests'::regclass
                  and conname = 'account_deletion_requests_expiry_check'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'account_deletion_requests'
                  and indexname = 'account_deletion_requests_user_status_idx'
                """, Integer.class)).isOne();

        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, email, registration_state, password_hash, role,
                    active, failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('first@example.com', 'First', 'same@example.com', 'PENDING_EMAIL_VERIFICATION', '{bcrypt}hash',
                    'OWNER', false, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.app_users (username, display_name, email, registration_state, password_hash, role,
                    active, failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('second@example.com', 'Second', 'same@example.com', 'PENDING_EMAIL_VERIFICATION', '{bcrypt}hash',
                    'OWNER', false, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active,
                    failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('v32-postgres-schema-user', 'V32 PostgreSQL', '{bcrypt}hash', 'OWNER', true,
                    0, current_timestamp, current_timestamp, current_timestamp, 0)
                """);
        Long userId = jdbcTemplate.queryForObject("""
                select id from public.app_users where username = 'v32-postgres-schema-user'
                """, Long.class);
        String tokenHash = "b".repeat(64);
        jdbcTemplate.update("""
                insert into public.account_deletion_requests
                    (id, user_id, source, status, token_hash, requested_at, expires_at)
                values (?, ?, 'PUBLIC', 'PENDING_CONFIRMATION', ?, current_timestamp, current_timestamp + interval '1 hour')
                """, UUID.randomUUID(), userId, tokenHash);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.account_deletion_requests
                    (id, user_id, source, status, token_hash, requested_at, expires_at)
                values (?, ?, 'PUBLIC', 'PENDING_CONFIRMATION', ?, current_timestamp, current_timestamp + interval '1 hour')
                """, UUID.randomUUID(), userId, tokenHash)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.account_deletion_requests
                    (id, user_id, source, status, requested_at, expires_at)
                values (?, ?, 'PUBLIC', 'PENDING_CONFIRMATION', current_timestamp, current_timestamp + interval '1 hour')
                """, UUID.randomUUID(), userId + 100000L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.account_deletion_requests
                    (id, user_id, source, status, requested_at, expires_at)
                values (?, ?, 'PUBLIC', 'PENDING_CONFIRMATION', current_timestamp, current_timestamp)
                """, UUID.randomUUID(), userId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("update public.app_users set registration_state = 'DELETED' where id = ?", userId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update public.app_users set registration_state = 'UNSUPPORTED_STATE' where id = ?
                """, userId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
                .defaultSchema("public")
                .schemas("public")
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .cleanDisabled(true)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
