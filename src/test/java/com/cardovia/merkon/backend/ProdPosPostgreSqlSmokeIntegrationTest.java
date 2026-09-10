package com.cardovia.merkon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cardovia.merkon.backend.agent.AiConversationService;
import com.cardovia.merkon.backend.businessday.BusinessDayError;
import com.cardovia.merkon.backend.businessday.BusinessDayException;
import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.businessday.CashExpenseRequest;
import com.cardovia.merkon.backend.businessday.CashExpenseService;
import com.cardovia.merkon.backend.businessday.CloseBusinessDayRequest;
import com.cardovia.merkon.backend.businessday.OpenBusinessDayRequest;
import com.cardovia.merkon.backend.configuration.WebConfig;
import com.cardovia.merkon.backend.security.AppUserRepository;
import com.cardovia.merkon.backend.security.ApplicationRole;
import com.cardovia.merkon.backend.security.AuthSessionRepository;
import com.cardovia.merkon.backend.security.AuthSessionService;
import com.cardovia.merkon.backend.security.CreateUserRequest;
import com.cardovia.merkon.backend.security.LoginRequest;
import com.cardovia.merkon.backend.security.PublicRegistrationRequest;
import com.cardovia.merkon.backend.security.PublicRegistrationResponse;
import com.cardovia.merkon.backend.security.PublicRegistrationService;
import com.cardovia.merkon.backend.security.SecurityApiException;
import com.cardovia.merkon.backend.security.UserManagementService;
import com.cardovia.merkon.backend.security.UserResponse;
import com.cardovia.merkon.backend.service.WhatsAppService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod-pos")
class ProdPosPostgreSqlSmokeIntegrationTest {

    private static final String OWNER_USERNAME = "postgres-smoke-owner";
    private static final String OWNER_PASSWORD = "Frase de prueba segura PostgreSQL 2026";
    private static final PemFiles PEM_FILES = PemFiles.create();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("merkon_smoke")
            .withUsername("merkon_smoke")
            .withPassword("merkon_smoke_password");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PublicRegistrationService registrations;

    @Autowired
    private UserManagementService userManagement;

    @Autowired
    private AuthSessionService authSessions;

    @Autowired
    private AuthSessionRepository sessionRepository;

    @Autowired
    private BusinessDayService businessDayService;

    @Autowired
    private CashExpenseService cashExpenseService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void productionLikeProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("MERKON_JWT_PRIVATE_KEY_LOCATION", () -> PEM_FILES.privateKeyFile().toUri().toString());
        registry.add("MERKON_JWT_PUBLIC_KEY_LOCATION", () -> PEM_FILES.publicKeyFile().toUri().toString());
        registry.add("MERKON_BOOTSTRAP_OWNER_USERNAME", () -> OWNER_USERNAME);
        registry.add("MERKON_BOOTSTRAP_OWNER_PASSWORD", () -> OWNER_PASSWORD);
        registry.add("MERKON_BOOTSTRAP_OWNER_DISPLAY_NAME", () -> "PostgreSQL Smoke Owner");
    }

    @AfterAll
    static void removeTemporaryKeys() throws IOException {
        Files.deleteIfExists(PEM_FILES.privateKeyFile());
        Files.deleteIfExists(PEM_FILES.publicKeyFile());
        Files.deleteIfExists(PEM_FILES.directory());
    }

    @Test
    void prodPosBootstrapsAStandardEmptyPostgreSqlDatabaseWithFileJwtKeysAndOwnerLogin() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("show server_version", String.class)).startsWith("17.");
        assertThat(environment.getProperty("DB_URL")).isEqualTo(POSTGRES.getJdbcUrl());
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.flyway_schema_history where success", Integer.class)).isEqualTo(29);
        assertThat(jdbcTemplate.queryForList(
                        "select script from public.flyway_schema_history where success order by installed_rank",
                        String.class))
                .containsExactly(
                        "B1__current_application_schema.sql",
                        "V2__add_parallel_numeric_money_columns.sql",
                        "V3__backfill_and_constrain_numeric_money.sql",
                        "V4__add_whatsapp_inbound_message_idempotency.sql",
                        "V5__add_structured_order_foundations.sql",
                        "V6__add_operational_menu_catalog.sql",
                        "V7__add_configurable_catalog_domain.sql",
                        "V8__add_temporal_promotions.sql",
                        "V9__add_application_security.sql",
                        "V10__add_manual_pos_order_foundations.sql",
                        "V11__add_authoritative_catalog_rules.sql",
                        "V12__add_authoritative_promotion_rules.sql",
                        "V13__repair_classic_roll_promotion_targets.sql",
                        "V14__persist_whatsapp_inbound_failure_diagnostics.sql",
                        "V15__add_flexible_promotion_rewards.sql",
                        "V16__add_historical_order_provenance.sql",
                        "V17__add_vendis_historical_sales_import.sql",
                        "V18__add_business_day_cash_reconciliation.sql",
                        "V19__add_business_day_reopen_history.sql",
                        "V20__add_order_flexibility.sql",
                        "V21__enforce_unique_promotion_targets.sql",
                        "V22__add_nested_customization_and_manual_priced_lines.sql",
                "V23__add_pos_order_void_audit.sql",
                "V24__add_business_day_cash_expenses.sql",
                "V25__add_pay_on_delivery_payment_timing.sql",
                "V26__allow_pickup_pay_on_delivery.sql",
                "V27__add_business_membership_foundation.sql",
                "V28__scope_operational_data_to_business.sql",
                "V29__add_public_registration_foundation.sql");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.flyway_schema_history where success and version = '27'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.flyway_schema_history where success and version = '28'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.flyway_schema_history where success and version = '29'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'user_terms_acceptances'
                """, Integer.class)).isOne();

        assertThat(userRepository.findByUsername(OWNER_USERNAME)).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where username = ? and role = 'OWNER'", Integer.class, OWNER_USERNAME)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                        select count(*) from public.business_memberships membership
                        join public.app_users user_account on user_account.id = membership.user_id
                        where user_account.username = ? and membership.role = 'OWNER'
                        """, Integer.class, OWNER_USERNAME)).isOne();
        String passwordHash = jdbcTemplate.queryForObject(
                "select password_hash from public.app_users where username = ?", String.class, OWNER_USERNAME);
        assertThat(passwordHash).startsWith("{bcrypt}").isNotEqualTo(OWNER_PASSWORD);

        JsonNode login = login();
        String accessToken = login.required("accessToken").asText();
        Jwt jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(OWNER_USERNAME))
                .andExpect(jsonPath("$.role").value("OWNER"));

        assertThat(applicationContext.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AiConversationService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WhatsAppService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WebConfig.class)).isEmpty();
        assertThat(environment.getProperty("storage.receipts-directory")).isEmpty();
        assertThat(environment.getProperty("storage.public-upload-directory")).isEmpty();
    }

    @Test
    void prodPosPostgreSqlRegistrationCreatesAnEmptyPendingOwnerBusiness() throws Exception {
        String registrationEmail = "postgres-registration@example.com";
        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", registrationEmail,
                                "displayName", "PostgreSQL registration",
                                "password", "una frase larga segura PostgreSQL 2026",
                                "businessName", "PostgreSQL registration business",
                                "termsAccepted", true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Solicitud de registro aceptada."));

        Long userId = jdbcTemplate.queryForObject(
                "select id from public.app_users where email = ?", Long.class, registrationEmail);
        Long businessId = jdbcTemplate.queryForObject("""
                select membership.business_id
                from public.business_memberships membership
                where membership.user_id = ? and membership.role = 'OWNER'
                """, Long.class, userId);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.app_users
                where id = ? and username = ? and registration_state = 'PENDING_EMAIL_VERIFICATION'
                  and active = false and email_verified_at is null
                """, Integer.class, userId, registrationEmail)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.user_terms_acceptances
                where user_id = ? and terms_version = 'v1'
                """, Integer.class, userId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select legacy_key from public.businesses where id = ?", String.class, businessId))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items where business_id = ?", Integer.class,
                businessId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotions where business_id = ?", Integer.class,
                businessId)).isZero();
    }

    @Test
    void prodPosPostgreSqlConcurrentEquivalentRegistrationsCreateExactlyOneOwnerBusiness() throws Exception {
        String email = "postgres-concurrent-registration@example.com";
        PublicRegistrationRequest request = new PublicRegistrationRequest(
                "  POSTGRES-CONCURRENT-REGISTRATION@EXAMPLE.COM  ",
                "PostgreSQL concurrent registration",
                "una frase larga segura PostgreSQL 2026",
                "PostgreSQL concurrent registration business",
                true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<PublicRegistrationResponse>> results = List.of(
                    executor.submit(() -> registerAtBarrier(request, ready, start)),
                    executor.submit(() -> registerAtBarrier(request, ready, start)));
            await(ready, "The concurrent PostgreSQL registrations did not reach the start barrier");
            start.countDown();
            for (Future<PublicRegistrationResponse> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).message()).isEqualTo("Solicitud de registro aceptada.");
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        Long userId = jdbcTemplate.queryForObject(
                "select id from public.app_users where email = ?", Long.class, email);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where email = ?", Integer.class, email)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.business_memberships
                where user_id = ? and role = 'OWNER'
                """, Integer.class, userId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.businesses business
                join public.business_memberships membership on membership.business_id = business.id
                where membership.user_id = ? and business.legacy_key is null
                """, Integer.class, userId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.user_terms_acceptances where user_id = ?", Integer.class, userId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select max(attempt_count) from public.registration_rate_limit_buckets", Integer.class)).isEqualTo(2);
    }

    @Test
    void prodPosPostgreSqlLegacyUnicodeUsernameBlocksEquivalentPunycodePublicRegistration() throws Exception {
        String legacyUsername = "owner@b\u00fccher.de";
        String canonicalEmail = "owner@xn--bcher-kva.de";
        int usersBefore = jdbcTemplate.queryForObject("select count(*) from public.app_users", Integer.class);
        int businessesBefore = jdbcTemplate.queryForObject(
                "select count(*) from public.businesses where legacy_key is null", Integer.class);
        int membershipsBefore = jdbcTemplate.queryForObject("select count(*) from public.business_memberships", Integer.class);
        int termsBefore = jdbcTemplate.queryForObject("select count(*) from public.user_terms_acceptances", Integer.class);
        int acceptedAuditsBefore = jdbcTemplate.queryForObject("""
                select count(*) from public.security_audit_events where event_type = 'REGISTRATION_ACCEPTED'
                """, Integer.class);

        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active,
                    failed_login_attempts, password_changed_at, created_at, updated_at, version)
                values (?, 'Legacy Unicode Identity', '{bcrypt}not-used', 'MANAGER', true,
                    0, current_timestamp, current_timestamp, current_timestamp, 0)
                """, legacyUsername);

        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", canonicalEmail,
                                "displayName", "Blocked PostgreSQL owner",
                                "password", "una frase larga segura PostgreSQL 2026",
                                "businessName", "Blocked PostgreSQL business",
                                "termsAccepted", true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Solicitud de registro aceptada."));

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.app_users", Integer.class))
                .isEqualTo(usersBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where username = ?", Integer.class, legacyUsername)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where email = ?", Integer.class, canonicalEmail)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.businesses where legacy_key is null", Integer.class)).isEqualTo(businessesBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.business_memberships", Integer.class))
                .isEqualTo(membershipsBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.user_terms_acceptances", Integer.class))
                .isEqualTo(termsBefore);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.security_audit_events where event_type = 'REGISTRATION_ACCEPTED'
                """, Integer.class)).isEqualTo(acceptedAuditsBefore);
    }

    @Test
    void prodPosPostgreSqlPublicCanonicalIdentityBlocksUnicodeEquivalentAdministrativeUsername() {
        String canonicalEmail = "administrator@xn--bcher-kva.example";
        registrations.register(new PublicRegistrationRequest(
                canonicalEmail, "Public PostgreSQL owner", "una frase larga segura PostgreSQL 2026",
                "Public PostgreSQL owner business", true), "198.51.100.30");
        Long ownerId = userRepository.findByUsername(OWNER_USERNAME).orElseThrow().getId();
        Long legacyBusinessId = jdbcTemplate.queryForObject("""
                select membership.business_id
                from public.business_memberships membership
                where membership.user_id = ? and membership.role = 'OWNER'
                """, Long.class, ownerId);

        assertThatThrownBy(() -> userManagement.create(new CreateUserRequest(
                "ADMINISTRATOR@B\u00dcCHER.EXAMPLE", "Colliding PostgreSQL administrator",
                "una frase larga segura PostgreSQL 2026", ApplicationRole.CASHIER),
                ownerId, legacyBusinessId, "127.0.0.1"))
                .isInstanceOf(SecurityApiException.class)
                .satisfies(exception -> assertThat(((SecurityApiException) exception).code()).isEqualTo("INVALID_USER"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where username = ?", Integer.class, canonicalEmail)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where username = ?", Integer.class,
                "administrator@b\u00fccher.example")).isZero();

        UserResponse canonicalAdministrator = userManagement.create(new CreateUserRequest(
                "CANONICAL@B\u00dcCHER.EXAMPLE", "Canonical PostgreSQL administrator",
                "una frase larga segura PostgreSQL 2026", ApplicationRole.KITCHEN),
                ownerId, legacyBusinessId, "127.0.0.1");
        assertThat(canonicalAdministrator.username()).isEqualTo("canonical@xn--bcher-kva.example");
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.app_users where username = ?", Integer.class,
                "canonical@xn--bcher-kva.example")).isOne();
    }

    @Test
    void prodPosPostgreSqlControlledLegacyOwnerIdentityConvergencePreservesReferencesAndLogin() throws Exception {
        Long bootstrapOwnerId = userRepository.findByUsername(OWNER_USERNAME).orElseThrow().getId();
        Long legacyBusinessId = jdbcTemplate.queryForObject("""
                select membership.business_id
                from public.business_memberships membership
                where membership.user_id = ? and membership.role = 'OWNER'
                """, Long.class, bootstrapOwnerId);
        String oldUsername = "postgres-legacy-owner-login";
        String canonicalEmail = "owner@xn--bcher-kva.example";
        String password = "una frase larga segura PostgreSQL 2026";
        UserResponse legacyOwner = userManagement.create(new CreateUserRequest(
                oldUsername, "PostgreSQL legacy owner", password, ApplicationRole.OWNER),
                bootstrapOwnerId, legacyBusinessId, "127.0.0.1");
        Long userId = legacyOwner.id();
        Long membershipId = jdbcTemplate.queryForObject("""
                select id from public.business_memberships
                where user_id = ? and business_id = ?
                """, Long.class, userId, legacyBusinessId);
        String passwordHash = jdbcTemplate.queryForObject(
                "select password_hash from public.app_users where id = ?", String.class, userId);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                oldUsername, password, "postgres-legacy-owner-pre-convergence", null, null))))
                .andExpect(status().isOk());
        UUID oldSessionId = sessionRepository.findActiveByUserAndDevice(userId, "postgres-legacy-owner-pre-convergence")
                .get(0).getId();

        assertThat(jdbcTemplate.update("""
                update public.app_users
                set username = ?, email = ?, updated_at = current_timestamp, version = version + 1
                where id = ?
                """, canonicalEmail, canonicalEmail, userId)).isOne();
        authSessions.revokeAll(userId, "IDENTITY_CONVERGED", userId, "127.0.0.1");

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.app_users
                where id = ? and username = ? and email = ? and password_hash = ?
                  and active = true and registration_state = 'LEGACY'
                """, Integer.class, userId, canonicalEmail, canonicalEmail, passwordHash)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.business_memberships
                where id = ? and user_id = ? and business_id = ? and role = 'OWNER'
                """, Integer.class, membershipId, userId, legacyBusinessId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.auth_sessions
                where id = ? and revoked_at is not null and revoke_reason = 'IDENTITY_CONVERGED'
                """, Integer.class, oldSessionId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.user_terms_acceptances where user_id = ?", Integer.class, userId)).isZero();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                oldUsername, password, "postgres-legacy-owner-old-login", null, null))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                canonicalEmail, password, "postgres-legacy-owner-canonical-login", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void prodPosPostgreSqlCashExpenseWaitingOnCloseLockReturnsOpenDayDomainError() throws Exception {
        resetBusinessDayState();
        Long ownerId = userRepository.findByUsername(OWNER_USERNAME).orElseThrow().getId();
        businessDayService.open(ownerId, new OpenBusinessDayRequest(new BigDecimal("500.00")));

        CountDownLatch openBusinessDayRowLocked = new CountDownLatch(1);
        CountDownLatch releaseOpenBusinessDayRow = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<?> rowLock = executor.submit(() -> lockOpenBusinessDayRow(openBusinessDayRowLocked, releaseOpenBusinessDayRow));
        try {
            await(openBusinessDayRowLocked, "The test transaction did not lock the open business-day row");

            Future<?> close = executor.submit(() -> businessDayService.close(ownerId,
                    new CloseBusinessDayRequest(new BigDecimal("500.00"))));
            awaitDatabaseLockWait("business_days");

            Future<ExpenseAttempt> expense = executor.submit(() -> {
                try {
                    cashExpenseService.create(ownerId, new CashExpenseRequest(UUID.randomUUID(), new BigDecimal("20.00"),
                            "Compra operativa", null));
                    return ExpenseAttempt.succeeded();
                } catch (BusinessDayException exception) {
                    return new ExpenseAttempt(exception.getError(), null);
                } catch (Throwable exception) {
                    return new ExpenseAttempt(null, exception);
                }
            });
            awaitDatabaseLockWait("business_day_operation_locks");

            releaseOpenBusinessDayRow.countDown();
            rowLock.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            ExpenseAttempt losingExpense = expense.get(5, TimeUnit.SECONDS);

            assertThat(losingExpense.unexpectedFailure()).isNull();
            assertThat(losingExpense.error()).isEqualTo(BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from public.business_day_cash_expenses", Integer.class))
                    .isZero();
        } finally {
            releaseOpenBusinessDayRow.countDown();
            executor.shutdownNow();
        }
    }

    private void lockOpenBusinessDayRow(CountDownLatch locked, CountDownLatch release) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("""
                    select id from public.business_days
                    where status = 'OPEN'
                    for update
                    """)) {
                if (!rows.next()) {
                    throw new AssertionError("The test could not find the open business day to lock");
                }
            }
            locked.countDown();
            await(release, "The test did not release the open business-day row");
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not hold the PostgreSQL open business-day row lock", exception);
        }
    }

    private void awaitDatabaseLockWait(String queryFragment) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*)
                    from pg_stat_activity
                    where datname = current_database()
                      and wait_event_type = 'Lock'
                      and lower(query) like concat('%', ?, '%')
                    """, Integer.class, queryFragment);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for PostgreSQL lock contention on " + queryFragment);
    }

    private static void await(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, exception);
        }
    }

    private void resetBusinessDayState() {
        jdbcTemplate.update("delete from public.business_day_cash_expenses");
        jdbcTemplate.update("delete from public.business_day_closures");
        jdbcTemplate.update("delete from public.business_days");
    }

    private record ExpenseAttempt(BusinessDayError error, Throwable unexpectedFailure) {

        static ExpenseAttempt succeeded() {
            return new ExpenseAttempt(null, null);
        }
    }

    private PublicRegistrationResponse registerAtBarrier(PublicRegistrationRequest request,
                                                          CountDownLatch ready,
                                                          CountDownLatch start) {
        ready.countDown();
        await(start, "The concurrent PostgreSQL registration test did not start");
        return registrations.register(request, "198.51.100.42");
    }

    private JsonNode login() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", OWNER_USERNAME,
                                "password", OWNER_PASSWORD,
                                "deviceId", "postgres-smoke-device",
                                "deviceName", "PostgreSQL smoke test",
                                "appVersion", "test"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private record PemFiles(Path directory, Path privateKeyFile, Path publicKeyFile) {

        static PemFiles create() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keys = generator.generateKeyPair();
                Path directory = Files.createTempDirectory("merkon-prod-pos-postgres-smoke-");
                Path privateKey = directory.resolve("private.pem");
                Path publicKey = directory.resolve("public.pem");
                Files.writeString(privateKey, pem("PRIVATE KEY", keys.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
                Files.writeString(publicKey, pem("PUBLIC KEY", keys.getPublic().getEncoded()), StandardCharsets.US_ASCII);
                privateKey.toFile().deleteOnExit();
                publicKey.toFile().deleteOnExit();
                directory.toFile().deleteOnExit();
                return new PemFiles(directory, privateKey, publicKey);
            } catch (GeneralSecurityException | IOException exception) {
                throw new IllegalStateException("Could not create temporary RSA keys for PostgreSQL prod-pos smoke test", exception);
            }
        }

        private static String pem(String type, byte[] encoded) {
            return "-----BEGIN " + type + "-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                    + "\n-----END " + type + "-----\n";
        }
    }
}
