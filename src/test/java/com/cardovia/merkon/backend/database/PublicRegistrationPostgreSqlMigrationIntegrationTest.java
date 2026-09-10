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

/** Exercises the unshipped V29 upgrade on the production database engine. */
@Testcontainers(disabledWithoutDocker = true)
class PublicRegistrationPostgreSqlMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("merkon_registration_migration")
            .withUsername("merkon_registration")
            .withPassword("merkon_registration_password");

    @Test
    void v28ToV29PreservesLegacyUsersAndEnforcesPublicRegistrationConstraints() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        flyway(dataSource, MigrationVersion.fromVersion("28")).migrate();
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active,
                    failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values ('legacy-v29-postgres', 'Legacy V29 PostgreSQL', '{bcrypt}hash', 'MANAGER', true,
                    0, current_timestamp, current_timestamp, current_timestamp, 0)
                """);

        flyway(dataSource, null).migrate();

        assertThat(jdbcTemplate.queryForObject("""
                select "version" from public.flyway_schema_history
                where success and "version" is not null
                order by installed_rank desc limit 1
                """, String.class)).isEqualTo("29");
        assertThat(jdbcTemplate.queryForObject("""
                select username from public.app_users where username = 'legacy-v29-postgres'
                """, String.class)).isEqualTo("legacy-v29-postgres");
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
