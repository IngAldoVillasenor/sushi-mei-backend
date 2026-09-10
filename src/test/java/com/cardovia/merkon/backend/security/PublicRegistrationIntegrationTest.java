package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.businessday.OpenBusinessDayRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "merkon.registration.transport-rate-limit-max-attempts=5")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, PublicRegistrationIntegrationTest.TestInfrastructureConfiguration.class})
class PublicRegistrationIntegrationTest {

    private static final String PASSWORD = "una frase larga segura 123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PublicRegistrationService registrations;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private TermsAcceptanceRepository termsAcceptances;
    @Autowired private PasswordPolicyService passwords;
    @Autowired private AuthService authService;
    @Autowired private AuthSessionService authSessions;
    @Autowired private AuthSessionRepository sessionRepository;
    @Autowired private BusinessDayService businessDays;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @BeforeEach
    void cleanFixtures() {
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
        jdbcTemplate.update("delete from public.user_terms_acceptances");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream()
                .filter(business -> business.getLegacyKey() == null)
                .forEach(businesses::delete);
    }

    @Test
    void anonymousRegistrationCreatesOnePendingOwnerBusinessAndVersionedConsentWithoutOperationalData() throws Exception {
        String response = registerHttp("  Ana.Owner@Example.COM  ", "  Ana Owner  ", "  Nuevo negocio  ")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Solicitud de registro aceptada."))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);

        assertThat(body.size()).isOne();
        assertThat(body.has("message")).isTrue();
        AppUser user = users.findByEmail("ana.owner@example.com").orElseThrow();
        assertThat(user.getUsername()).isEqualTo("ana.owner@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Ana Owner");
        assertThat(user.getRegistrationState()).isEqualTo(AccountRegistrationState.PENDING_EMAIL_VERIFICATION);
        assertThat(user.isActive()).isFalse();
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();

        BusinessMembership membership = memberships.findByUserIdOrderByIdAsc(user.getId()).get(0);
        Business business = membership.getBusiness();
        assertThat(membership.getRole()).isEqualTo(ApplicationRole.OWNER);
        assertThat(business.isActive()).isTrue();
        assertThat(business.getLegacyKey()).isNull();
        assertThat(termsAcceptances.findByUserIdOrderByAcceptedAtAsc(user.getId())).singleElement()
                .satisfies(acceptance -> {
                    assertThat(acceptance.getTermsVersion()).isEqualTo("v1");
                    assertThat(acceptance.getAcceptedAt()).isNotNull();
                });
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_ACCEPTED'",
                Integer.class)).isOne();

        assertTenantHasNoOperationalData(business.getId());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                user.getUsername(), PASSWORD, "pending-registration-device", null, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void duplicateCaseAndWhitespaceEquivalentRegistrationsReturnTheSameGenericResponseWithoutNewTenant() throws Exception {
        String first = registerHttp("owner@example.com", "Owner", "Business One")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String caseVariant = registerHttp(" OWNER@EXAMPLE.COM ", "Other", "Business Two")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String whitespaceVariant = registerHttp("  owner@example.com  ", "Other again", "Business Three")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(first).isEqualTo(caseVariant).isEqualTo(whitespaceVariant);
        assertThat(users.findByEmail("owner@example.com")).isPresent();
        assertThat(nonLegacyBusinesses()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.business_memberships", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.user_terms_acceptances", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_REJECTED'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentEquivalentRegistrationsPersistExactlyOneIdentityBusinessMembershipAndAcceptance() throws Exception {
        PublicRegistrationRequest request = request("  CONCURRENT@EXAMPLE.COM ", "Concurrent owner", "Concurrent business");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<PublicRegistrationResponse>> results = List.of(
                    executor.submit(() -> registerAtBarrier(request, ready, start)),
                    executor.submit(() -> registerAtBarrier(request, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<PublicRegistrationResponse> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).message()).isEqualTo("Solicitud de registro aceptada.");
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        AppUser user = users.findByEmail("concurrent@example.com").orElseThrow();
        assertThat(nonLegacyBusinesses()).hasSize(1);
        assertThat(memberships.findByUserIdOrderByIdAsc(user.getId())).hasSize(1);
        assertThat(termsAcceptances.findByUserIdOrderByAcceptedAtAsc(user.getId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.app_users where email = 'concurrent@example.com'",
                Integer.class)).isOne();
    }

    @Test
    void registrationRateLimitIsDatabaseBackedAndDoesNotRevealIdentityState() throws Exception {
        for (int index = 1; index <= 5; index++) {
            registerHttp("rate" + index + "@example.com", "Rate " + index, "Rate business " + index)
                    .andExpect(status().isAccepted());
        }
        registerHttp("rate6@example.com", "Rate 6", "Rate business 6")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.registration_rate_limit_buckets", Integer.class))
                .isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("select max(attempt_count) from public.registration_rate_limit_buckets", Integer.class))
                .isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.security_audit_events
                where event_type = 'REGISTRATION_REJECTED' and reason_code = 'RATE_LIMITED'
                """, Integer.class)).isOne();
        assertThat(nonLegacyBusinesses()).hasSize(5);
    }

    @Test
    void invalidHttpRegistrationAttemptsAndUntrustedForwardedHeadersStillConsumeOneTransportBucket() throws Exception {
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(post("/api/v1/registration")
                            .header("X-Forwarded-For", "198.51.100." + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "not-an-email",
                                    "displayName", "Invalid " + index,
                                    "password", PASSWORD,
                                    "businessName", "Invalid business " + index,
                                    "termsAccepted", true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_INVALID_REQUEST"));
        }
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(post("/api/v1/registration")
                            .header("X-Forwarded-For", "203.0.113." + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "terms" + index + "@example.com",
                                    "displayName", "Terms " + index,
                                    "password", PASSWORD,
                                    "businessName", "Terms business " + index,
                                    "termsAccepted", false))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_INVALID_REQUEST"));
        }
        mockMvc.perform(post("/api/v1/registration")
                        .header("X-Forwarded-For", "192.0.2.55")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTRATION_INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/registration")
                        .header("X-Forwarded-For", "192.0.2.56")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "sixth@example.com",
                                "displayName", "Sixth",
                                "password", PASSWORD,
                                "businessName", "Sixth business",
                                "termsAccepted", false))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.registration_rate_limit_buckets", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("select attempt_count from public.registration_rate_limit_buckets", Integer.class))
                .isEqualTo(6);
        assertThat(users.count()).isZero();
        assertThat(nonLegacyBusinesses()).isEmpty();
    }

    @Test
    void oneValidHttpRegistrationConsumesEachApplicableBucketExactlyOnce() throws Exception {
        registerHttp("one-attempt@example.com", "One attempt", "One attempt business")
                .andExpect(status().isAccepted());

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.registration_rate_limit_buckets", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select min(attempt_count) from public.registration_rate_limit_buckets", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("select max(attempt_count) from public.registration_rate_limit_buckets", Integer.class))
                .isOne();
    }

    @Test
    void canonicalIdentityBucketLimitsEquivalentRetriesIndependentlyOfTransportAddress() {
        PublicRegistrationRequest request = request("identity-limit@example.com", "Identity limit", "Identity business");
        for (int index = 0; index < 5; index++) {
            assertThat(registrations.register(request, "198.51.100." + index).message())
                    .isEqualTo("Solicitud de registro aceptada.");
        }

        assertThatThrownBy(() -> registrations.register(request, "203.0.113.55"))
                .isInstanceOf(SecurityApiException.class)
                .satisfies(exception -> assertThat(((SecurityApiException) exception).code())
                        .isEqualTo("REGISTRATION_RATE_LIMITED"));
        assertThat(users.findByEmail("identity-limit@example.com")).isPresent();
        assertThat(nonLegacyBusinesses()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.registration_rate_limit_buckets", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("select attempt_count from public.registration_rate_limit_buckets", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void idnEmailIsCanonicalizedForDuplicateRegistrationAndPendingLoginResolution() throws Exception {
        registerHttp("owner@b\u00fccher.de", "IDN owner", "IDN business")
                .andExpect(status().isAccepted());
        registerHttp("OWNER@XN--BCHER-KVA.DE", "Duplicate IDN owner", "Other IDN business")
                .andExpect(status().isAccepted());

        AppUser user = users.findByEmail("owner@xn--bcher-kva.de").orElseThrow();
        assertThat(user.getUsername()).isEqualTo("owner@xn--bcher-kva.de");
        assertThat(nonLegacyBusinesses()).hasSize(1);
        assertThat(memberships.findByUserIdOrderByIdAsc(user.getId())).singleElement()
                .extracting(BusinessMembership::getRole)
                .isEqualTo(ApplicationRole.OWNER);
        assertThat(termsAcceptances.findByUserIdOrderByAcceptedAtAsc(user.getId())).hasSize(1);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                "OWNER@b\u00fccher.de", PASSWORD, "idn-pending-device", null, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.security_audit_events
                where event_type = 'LOGIN_FAILURE' and subject_user_id = ?
                """, Integer.class, user.getId())).isOne();
    }

    @Test
    void existingUnicodeLegacyUsernameReservesTheEquivalentPunycodePublicIdentity() throws Exception {
        AppUser legacy = createLegacyIdentity("owner@b\u00fccher.de", null);
        String response = registerHttp("owner@xn--bcher-kva.de", "Public owner", "Blocked business")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).isEqualTo(objectMapper.writeValueAsString(PublicRegistrationResponse.accepted()));
        assertNoPublicRegistrationWasCreated(legacy, 1);
    }

    @Test
    void existingPunycodeLegacyUsernameReservesTheEquivalentUnicodePublicIdentity() throws Exception {
        AppUser legacy = createLegacyIdentity("owner@xn--bcher-kva.de", null);
        String response = registerHttp("OWNER@B\u00dcCHER.DE", "Public owner", "Blocked business")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).isEqualTo(objectMapper.writeValueAsString(PublicRegistrationResponse.accepted()));
        assertNoPublicRegistrationWasCreated(legacy, 1);
    }

    @Test
    void existingUnicodeLegacyEmailReservesTheEquivalentPunycodePublicIdentity() throws Exception {
        AppUser legacy = createLegacyIdentity("legacy-email-user", "owner@b\u00fccher.de");
        String response = registerHttp("owner@xn--bcher-kva.de", "Public owner", "Blocked business")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).isEqualTo(objectMapper.writeValueAsString(PublicRegistrationResponse.accepted()));
        assertNoPublicRegistrationWasCreated(legacy, 1);
    }

    @Test
    void equivalentUsernameAndEmailCollisionsHaveTheSameEnumerationSafeResponse() throws Exception {
        AppUser usernameIdentity = createLegacyIdentity("username@b\u00fccher.de", null);
        AppUser emailIdentity = createLegacyIdentity("email-identity", "email@b\u00fccher.de");

        String usernameCollision = registerHttp("username@xn--bcher-kva.de", "First", "First business")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String emailCollision = registerHttp("EMAIL@XN--BCHER-KVA.DE", "Second", "Second business")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(usernameCollision).isEqualTo(emailCollision)
                .isEqualTo(objectMapper.writeValueAsString(PublicRegistrationResponse.accepted()));
        assertThat(users.count()).isEqualTo(2);
        assertThat(nonLegacyBusinesses()).isEmpty();
        assertThat(memberships.findByUserIdOrderByIdAsc(usernameIdentity.getId())).hasSize(1);
        assertThat(memberships.findByUserIdOrderByIdAsc(emailIdentity.getId())).hasSize(1);
        assertThat(termsAcceptances.count()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_ACCEPTED'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_REJECTED'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void invalidPasswordAndIgnoredAuthorityInjectionCannotCreateOrDowngradeTheOwnerMembership() throws Exception {
        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "weak@example.com",
                                "displayName", "Weak",
                                "password", "short",
                                "businessName", "Weak business",
                                "termsAccepted", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_REJECTED"));

        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"email":"authority@example.com","displayName":"Authority","password":"una frase larga segura 123",
                                  "businessName":"Authority business","termsAccepted":true,"role":"CASHIER","businessId":999}
                                """))
                .andExpect(status().isAccepted());

        assertThat(users.findByEmail("weak@example.com")).isEmpty();
        AppUser authority = users.findByEmail("authority@example.com").orElseThrow();
        assertThat(memberships.findByUserIdOrderByIdAsc(authority.getId())).singleElement()
                .extracting(BusinessMembership::getRole)
                .isEqualTo(ApplicationRole.OWNER);
        assertThat(nonLegacyBusinesses()).hasSize(1);
    }

    @Test
    void malformedEmailAndMissingTermsAcceptanceAreRejectedBeforeAnyRegistrationIsPersisted() throws Exception {
        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "not-an-email",
                                "displayName", "Invalid email",
                                "password", PASSWORD,
                                "businessName", "Invalid email business",
                                "termsAccepted", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTRATION_INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "terms@example.com",
                                "displayName", "Missing terms",
                                "password", PASSWORD,
                                "businessName", "Missing terms business",
                                "termsAccepted", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTRATION_INVALID_REQUEST"));

        assertThat(users.findByEmail("terms@example.com")).isEmpty();
        assertThat(nonLegacyBusinesses()).isEmpty();
    }

    @Test
    void legacyUsernameLoginRemainsAvailable() throws Exception {
        Business legacyBusiness = businesses.findByLegacyKey("SUSHIMEI_LEGACY").orElseThrow();
        AppUser legacy = users.saveAndFlush(AppUser.create(
                "legacy-login", "Legacy login", passwords.encodeValidated("legacy-login", PASSWORD),
                ApplicationRole.MANAGER, clock.instant()));
        memberships.saveAndFlush(BusinessMembership.create(
                legacy, legacyBusiness, ApplicationRole.MANAGER, clock.instant()));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                "legacy-login", PASSWORD, "legacy-registration-regression", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void legacyUsernameContainingAnInvalidEmailShapeRemainsAUsername() throws Exception {
        Business legacyBusiness = businesses.findByLegacyKey("SUSHIMEI_LEGACY").orElseThrow();
        AppUser legacy = users.saveAndFlush(AppUser.create(
                "legacy@not-email", "Legacy at username", passwords.encodeValidated("legacy@not-email", PASSWORD),
                ApplicationRole.MANAGER, clock.instant()));
        memberships.saveAndFlush(BusinessMembership.create(
                legacy, legacyBusiness, ApplicationRole.MANAGER, clock.instant()));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                "LEGACY@NOT-EMAIL", PASSWORD, "legacy-at-registration-regression", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void controlledLegacyOwnerIdentityConvergencePreservesReferencesRevokesSessionsAndUsesCanonicalEmailLogin()
            throws Exception {
        Business legacyBusiness = businesses.findByLegacyKey("SUSHIMEI_LEGACY").orElseThrow();
        String oldUsername = "legacy-owner-login";
        String canonicalEmail = "owner@xn--bcher-kva.example";
        AppUser legacyOwner = users.saveAndFlush(AppUser.create(
                oldUsername, "Legacy owner", passwords.encodeValidated(oldUsername, PASSWORD),
                ApplicationRole.OWNER, clock.instant()));
        BusinessMembership membership = memberships.saveAndFlush(BusinessMembership.create(
                legacyOwner, legacyBusiness, ApplicationRole.OWNER, clock.instant()));
        Long userId = legacyOwner.getId();
        Long membershipId = membership.getId();
        Long businessId = legacyBusiness.getId();
        String passwordHash = legacyOwner.getPasswordHash();

        authService.login(new LoginRequest(oldUsername, PASSWORD, "legacy-owner-pre-convergence", null, null), "127.0.0.1");
        UUID oldSessionId = sessionRepository.findActiveByUserAndDevice(userId, "legacy-owner-pre-convergence")
                .get(0).getId();
        businessDays.open(businessId, userId, new OpenBusinessDayRequest(new BigDecimal("100.00")));

        try {
            // This is the same controlled data mutation documented in the external runbook:
            // it changes only the login identity and does not manufacture verification or consent evidence.
            assertThat(jdbcTemplate.update("""
                    update public.app_users
                    set username = ?, email = ?, updated_at = current_timestamp, version = version + 1
                    where id = ?
                    """, canonicalEmail, canonicalEmail, userId)).isOne();
            authSessions.revokeAll(userId, "IDENTITY_CONVERGED", userId, "127.0.0.1");

            AppUser migrated = users.findById(userId).orElseThrow();
            BusinessMembership migratedMembership = memberships.findByUserIdAndBusinessId(userId, businessId).orElseThrow();
            assertThat(migrated.getId()).isEqualTo(userId);
            assertThat(migrated.getUsername()).isEqualTo(canonicalEmail);
            assertThat(migrated.getEmail()).isEqualTo(canonicalEmail);
            assertThat(migrated.getPasswordHash()).isEqualTo(passwordHash);
            assertThat(migrated.isActive()).isTrue();
            assertThat(migrated.getRegistrationState()).isEqualTo(AccountRegistrationState.LEGACY);
            assertThat(migratedMembership.getId()).isEqualTo(membershipId);
            assertThat(migratedMembership.getBusiness().getId()).isEqualTo(businessId);
            assertThat(migratedMembership.getRole()).isEqualTo(ApplicationRole.OWNER);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from public.business_days where business_id = ? and opened_by_user_id = ?",
                    Integer.class, businessId, userId)).isOne();
            assertThat(sessionRepository.findByIdWithContext(oldSessionId).orElseThrow().isRevoked()).isTrue();
            assertThat(sessionRepository.findByIdWithContext(oldSessionId).orElseThrow().getRevokeReason())
                    .isEqualTo("IDENTITY_CONVERGED");
            assertThat(termsAcceptances.findByUserIdOrderByAcceptedAtAsc(userId)).isEmpty();

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(
                                    oldUsername, PASSWORD, "legacy-owner-old-login", null, null))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(
                                    canonicalEmail, PASSWORD, "legacy-owner-canonical-login", null, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
        } finally {
            jdbcTemplate.update("delete from public.business_day_cash_expenses where business_day_id in "
                    + "(select id from public.business_days where business_id = ?)", businessId);
            jdbcTemplate.update("delete from public.business_day_closures where business_day_id in "
                    + "(select id from public.business_days where business_id = ?)", businessId);
            jdbcTemplate.update("delete from public.business_days where business_id = ?", businessId);
        }
    }

    private AppUser createLegacyIdentity(String username, String email) {
        AppUser legacy = AppUser.create(
                username,
                "Legacy " + username,
                passwords.encodeValidated(username, PASSWORD),
                ApplicationRole.MANAGER,
                clock.instant());
        if (email != null) {
            // Simulates a pre-V3 administrator/legacy record that stored a
            // valid Unicode IDN form before canonicalization was centralized.
            legacy.setNormalizedEmail(email, AccountRegistrationState.LEGACY, clock.instant());
        }
        legacy = users.saveAndFlush(legacy);
        Business legacyBusiness = businesses.findByLegacyKey("SUSHIMEI_LEGACY").orElseThrow();
        memberships.saveAndFlush(BusinessMembership.create(
                legacy, legacyBusiness, ApplicationRole.MANAGER, clock.instant()));
        return legacy;
    }

    private void assertNoPublicRegistrationWasCreated(AppUser legacy, int rejectedAuditCount) {
        assertThat(users.count()).isOne();
        assertThat(nonLegacyBusinesses()).isEmpty();
        assertThat(memberships.findByUserIdOrderByIdAsc(legacy.getId())).hasSize(1);
        assertThat(termsAcceptances.count()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_ACCEPTED'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_REJECTED'",
                Integer.class)).isEqualTo(rejectedAuditCount);
    }

    private org.springframework.test.web.servlet.ResultActions registerHttp(String email, String displayName, String businessName)
            throws Exception {
        return mockMvc.perform(post("/api/v1/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(email, displayName, businessName))));
    }

    private static PublicRegistrationRequest request(String email, String displayName, String businessName) {
        return new PublicRegistrationRequest(email, displayName, PASSWORD, businessName, true);
    }

    private PublicRegistrationResponse registerAtBarrier(PublicRegistrationRequest request,
                                                         CountDownLatch ready,
                                                         CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent registration test did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent registration test was interrupted", exception);
        }
        return registrations.register(request, "198.51.100.42");
    }

    private List<Business> nonLegacyBusinesses() {
        return businesses.findAll().stream().filter(business -> business.getLegacyKey() == null).toList();
    }

    private void assertTenantHasNoOperationalData(Long businessId) {
        for (String table : List.of("menu_items", "catalog_tags", "promotions", "orders", "business_days", "cart",
                "business_day_cash_expenses")) {
            assertThat(jdbcTemplate.queryForObject("select count(*) from public." + table + " where business_id = ?",
                    Integer.class, businessId)).isZero();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.vendis_order_snapshots", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.vendis_payment_snapshots", Integer.class)).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
