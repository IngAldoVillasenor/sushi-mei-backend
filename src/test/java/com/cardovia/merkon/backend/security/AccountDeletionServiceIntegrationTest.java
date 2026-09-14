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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Commit-level V3-A2 coverage for account-deletion lifecycle transitions and
 * physical session cleanup. These tests deliberately exercise the service's
 * transactions rather than just entity methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, AccountDeletionServiceIntegrationTest.TestInfrastructureConfiguration.class})
class AccountDeletionServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final String PASSWORD = "una frase larga segura delete 2026";

    @Autowired private AccountDeletionService deletion;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private AccountDeletionRequestRepository requests;
    @Autowired private AuthSessionService sessions;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CapturingTransactionalEmailSender sender;

    @BeforeEach
    void clean() {
        sender.clear();
        jdbc.update("delete from public.security_audit_events");
        jdbc.update("delete from public.account_deletion_requests");
        jdbc.update("delete from public.auth_refresh_token_history");
        jdbc.update("delete from public.auth_sessions");
        jdbc.update("delete from public.password_reset_tokens");
        jdbc.update("delete from public.email_verification_tokens");
        jdbc.update("delete from public.user_terms_acceptances");
        jdbc.update("delete from public.registration_rate_limit_buckets");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream().filter(business -> business.getLegacyKey() == null).forEach(businesses::delete);
    }

    @Test
    void publicLastOwnerActionRequiredCommitsAndConsumesTheTokenBeforeTheConflictResponse() throws Exception {
        Fixture fixture = fixture("public-last-owner@example.com", ApplicationRole.OWNER, false);
        RequestToken token = pending(fixture.user(), AccountDeletionSource.PUBLIC);

        mockMvc.perform(post("/api/v1/account-deletion/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountDeletionTokenInput(token.plaintext()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_LAST_OWNER_ACTION_REQUIRED"));

        AccountDeletionRequest actionRequired = requests.findById(token.requestId()).orElseThrow();
        assertThat(actionRequired.getStatus()).isEqualTo(AccountDeletionStatus.ACTION_REQUIRED);
        assertThat(actionRequired.getActionCode()).isEqualTo("LAST_OWNER");
        assertThat(actionRequired.getConfirmedAt()).isNotNull();
        assertThat(actionRequired.getCompletedAt()).isNull();
        assertThat(actionRequired.getTokenHash()).isNull();
        assertThat(users.findById(fixture.user().getId()).orElseThrow().isActive()).isTrue();
        assertThat(memberships.findByUserIdOrderByIdAsc(fixture.user().getId())).hasSize(1);

        mockMvc.perform(post("/api/v1/account-deletion/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountDeletionTokenInput(token.plaintext()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_INVALID_TOKEN"));
    }

    @Test
    void pendingAccountNeedingSupportPersistsItsOwnActionReasonInsteadOfLastOwner() throws Exception {
        AppUser pending = users.saveAndFlush(AppUser.createPendingRegistration(
                "pending-support@example.com", "Pending support", passwords.encode(PASSWORD), NOW));
        Business business = businesses.saveAndFlush(Business.create("Pending support business", NOW));
        memberships.saveAndFlush(BusinessMembership.create(pending, business, ApplicationRole.OWNER, NOW));
        AppUser colleague = activeUser("pending-colleague@example.com");
        memberships.saveAndFlush(BusinessMembership.create(colleague, business, ApplicationRole.MANAGER, NOW));
        RequestToken token = pending(pending, AccountDeletionSource.PUBLIC);

        mockMvc.perform(post("/api/v1/account-deletion/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountDeletionTokenInput(token.plaintext()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_ACTION_REQUIRED"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("ACCOUNT_DELETION_LAST_OWNER_ACTION_REQUIRED")));

        AccountDeletionRequest actionRequired = requests.findById(token.requestId()).orElseThrow();
        assertThat(actionRequired.getStatus()).isEqualTo(AccountDeletionStatus.ACTION_REQUIRED);
        assertThat(actionRequired.getActionCode()).isEqualTo("PENDING_ACCOUNT_SUPPORT_REQUIRED");
        assertThat(actionRequired.getConfirmedAt()).isNotNull();
        assertThat(actionRequired.getCompletedAt()).isNull();
        assertThat(actionRequired.getTokenHash()).isNull();
    }

    @Test
    void inAppActionRequiredAndSuccessfulLifecycleUseTruthfulTimestampsAndDoNotConfirmWrongPasswords() {
        Fixture lastOwner = fixture("in-app-last-owner@example.com", ApplicationRole.OWNER, false);
        assertThat(deletion.confirmInApp(lastOwner.user().getId(), PASSWORD))
                .isEqualTo(AccountDeletionOutcome.LAST_OWNER_ACTION_REQUIRED);
        AccountDeletionRequest blocked = onlyRequest(lastOwner.user());
        assertThat(blocked.getSource()).isEqualTo(AccountDeletionSource.IN_APP);
        assertThat(blocked.getStatus()).isEqualTo(AccountDeletionStatus.ACTION_REQUIRED);
        assertThat(blocked.getActionCode()).isEqualTo("LAST_OWNER");
        assertThat(blocked.getConfirmedAt()).isNotNull();
        assertThat(blocked.getCompletedAt()).isNull();

        Fixture wrongPassword = fixture("in-app-wrong-password@example.com", ApplicationRole.MANAGER, true);
        assertThatThrownBy(() -> deletion.confirmInApp(wrongPassword.user().getId(), "wrong password"))
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo("ACCOUNT_DELETION_REAUTH_FAILED");
        assertThat(requests.findAll().stream().filter(request -> request.getUser().getId().equals(wrongPassword.user().getId())))
                .isEmpty();
        assertThat(users.findById(wrongPassword.user().getId()).orElseThrow().isActive()).isTrue();
        assertThat(memberships.findByUserIdOrderByIdAsc(wrongPassword.user().getId())).hasSize(1);

        Fixture deletable = fixture("in-app-success@example.com", ApplicationRole.MANAGER, true);
        RequestToken priorPublic = pending(deletable.user(), AccountDeletionSource.PUBLIC);
        assertThat(deletion.confirmInApp(deletable.user().getId(), PASSWORD)).isEqualTo(AccountDeletionOutcome.COMPLETED);
        List<AccountDeletionRequest> lifecycle = requests.findAll().stream()
                .filter(request -> request.getUser().getId().equals(deletable.user().getId())).toList();
        AccountDeletionRequest completed = lifecycle.stream()
                .filter(request -> request.getSource() == AccountDeletionSource.IN_APP).findFirst().orElseThrow();
        AccountDeletionRequest superseded = requests.findById(priorPublic.requestId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AccountDeletionStatus.COMPLETED);
        assertThat(completed.getRequestedAt()).isBeforeOrEqualTo(completed.getConfirmedAt());
        assertThat(completed.getConfirmedAt()).isBeforeOrEqualTo(completed.getCompletedAt());
        assertThat(superseded.getStatus()).isEqualTo(AccountDeletionStatus.SUPERSEDED);
        assertThat(superseded.getTokenHash()).isNull();
        assertThat(superseded.getConfirmedAt()).isNull();
    }

    @Test
    void inAppLastOwnerRequestCommitsActionRequiredBeforeItsHttpConflictIsRendered() throws Exception {
        Fixture fixture = fixture("in-app-http-last-owner@example.com", ApplicationRole.OWNER, false);
        AuthSessionService.SessionToken session = openAndRotate(fixture, "in-app-last-owner-session");

        mockMvc.perform(post("/api/v1/auth/account-deletion/confirm")
                        .with(jwt(fixture, session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeletionReauthenticationRequest(PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_LAST_OWNER_ACTION_REQUIRED"));

        AccountDeletionRequest request = onlyRequest(fixture.user());
        assertThat(request.getStatus()).isEqualTo(AccountDeletionStatus.ACTION_REQUIRED);
        assertThat(request.getConfirmedAt()).isNotNull();
        assertThat(request.getCompletedAt()).isNull();
    }

    @Test
    void concurrentPublicRequestsSerializeReplacementAndOnlyTheLatestPendingTokenCanBeUsed() throws Exception {
        Fixture fixture = fixture("concurrent-deletion-request@example.com", ApplicationRole.MANAGER, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = List.of(
                    executor.submit(() -> requestAtBarrier(fixture.user().getEmail(), ready, start)),
                    executor.submit(() -> requestAtBarrier(fixture.user().getEmail(), ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        List<AccountDeletionRequest> created = requests.findAll().stream()
                .filter(request -> request.getUser().getId().equals(fixture.user().getId())).toList();
        assertThat(created).hasSize(2);
        assertThat(created.stream().filter(request -> request.getStatus() == AccountDeletionStatus.PENDING_CONFIRMATION)).hasSize(1);
        assertThat(created.stream().filter(request -> request.getStatus() == AccountDeletionStatus.SUPERSEDED)).hasSize(1);
        AccountDeletionRequest current = created.stream()
                .filter(request -> request.getStatus() == AccountDeletionStatus.PENDING_CONFIRMATION).findFirst().orElseThrow();
        List<String> deliveredTokens = sender.tokens(2);
        String currentPlaintext = deliveredTokens.stream()
                .filter(candidate -> tokens.hash(candidate).equals(current.getTokenHash())).findFirst().orElseThrow();
        String supersededPlaintext = deliveredTokens.stream()
                .filter(candidate -> !candidate.equals(currentPlaintext)).findFirst().orElseThrow();
        assertThat(deletion.confirmPublic(new AccountDeletionTokenInput(currentPlaintext)))
                .isEqualTo(AccountDeletionOutcome.COMPLETED);
        assertThat(requests.findById(current.getId()).orElseThrow().getStatus()).isEqualTo(AccountDeletionStatus.COMPLETED);
        assertThat(created.stream().filter(request -> !request.getId().equals(current.getId())).findFirst().orElseThrow().getStatus())
                .isEqualTo(AccountDeletionStatus.SUPERSEDED);
        assertThat(requests.findAll().stream().filter(request -> request.getUser().getId().equals(fixture.user().getId())
                && request.getStatus() == AccountDeletionStatus.PENDING_CONFIRMATION)).isEmpty();
        assertThatThrownBy(() -> deletion.confirmPublic(new AccountDeletionTokenInput(supersededPlaintext)))
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo("ACCOUNT_DELETION_INVALID_TOKEN");
    }

    @Test
    void accountDeletionPhysicallyRemovesOnlyItsSessionsRefreshHistoryAndMembershipsBeforeTombstoning() {
        Fixture fixture = fixture("account-session-cleanup@example.com", ApplicationRole.MANAGER, true);
        AuthSessionService.SessionToken first = openAndRotate(fixture, "account-cleanup-a");
        AuthSessionService.SessionToken second = openAndRotate(fixture, "account-cleanup-b");
        assertThat(jdbc.queryForObject("select count(*) from public.auth_refresh_token_history", Integer.class)).isEqualTo(2);

        assertThat(deletion.confirmInApp(fixture.user().getId(), PASSWORD)).isEqualTo(AccountDeletionOutcome.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from public.auth_sessions where user_id = ?", Integer.class, fixture.user().getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from public.auth_refresh_token_history", Integer.class)).isZero();
        assertThat(memberships.findByUserIdOrderByIdAsc(fixture.user().getId())).isEmpty();
        AppUser tombstone = users.findById(fixture.user().getId()).orElseThrow();
        assertThat(tombstone.getRegistrationState()).isEqualTo(AccountRegistrationState.DELETED);
        assertThat(tombstone.isActive()).isFalse();
        assertThat(tombstone.getEmail()).isNull();
        assertThat(tombstone.getUsername()).startsWith("deleted-");
        assertThat(first.session().getId()).isNotEqualTo(second.session().getId());
    }

    @Test
    void businessDeletionRemovesOnlyTargetBusinessSessionsAndRefreshHistory() {
        AppUser user = activeUser("cross-business-session@example.com");
        Business businessA = businesses.saveAndFlush(Business.create("Delete business A", NOW));
        Business businessB = businesses.saveAndFlush(Business.create("Keep business B", NOW));
        BusinessMembership membershipA = memberships.saveAndFlush(BusinessMembership.create(user, businessA, ApplicationRole.OWNER, NOW));
        BusinessMembership membershipB = memberships.saveAndFlush(BusinessMembership.create(user, businessB, ApplicationRole.MANAGER, NOW));
        AuthSessionService.SessionToken sessionA = openAndRotate(user, businessA, "delete-business-a");
        AuthSessionService.SessionToken sessionB = openAndRotate(user, businessB, "keep-business-b");

        deletion.deleteBusiness(user.getId(), businessA.getId(), PASSWORD);

        assertThat(jdbc.queryForObject("select count(*) from public.auth_sessions where id = ?", Integer.class, sessionA.session().getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from public.auth_refresh_token_history where session_id = ?", Integer.class, sessionA.session().getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from public.auth_sessions where id = ?", Integer.class, sessionB.session().getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public.auth_refresh_token_history where session_id = ?", Integer.class, sessionB.session().getId())).isEqualTo(1);
        assertThat(businesses.findById(businessA.getId())).isEmpty();
        assertThat(businesses.findById(businessB.getId())).isPresent();
        assertThat(memberships.findById(membershipA.getId())).isEmpty();
        assertThat(memberships.findById(membershipB.getId())).isPresent();
        assertThat(users.findById(user.getId())).isPresent();
    }

    @Test
    void deletionLifecycleWritesMinimalCommittedSecurityAuditEvidence() {
        Fixture blocked = fixture("audit-last-owner@example.com", ApplicationRole.OWNER, false);
        assertThat(deletion.confirmInApp(blocked.user().getId(), PASSWORD))
                .isEqualTo(AccountDeletionOutcome.LAST_OWNER_ACTION_REQUIRED);
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_ACTION_REQUIRED, blocked.user().getId())).isEqualTo(1);
        assertThat(auditReason(SecurityAuditEventType.ACCOUNT_DELETION_ACTION_REQUIRED, blocked.user().getId()))
                .isEqualTo("LAST_OWNER");

        Fixture deleted = fixture("audit-completed@example.com", ApplicationRole.MANAGER, true);
        assertThat(deletion.confirmInApp(deleted.user().getId(), PASSWORD)).isEqualTo(AccountDeletionOutcome.COMPLETED);
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_COMPLETED, deleted.user().getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public.security_audit_events where event_type = ? and subject_user_id = ? and (client_ip is not null or device_id is not null)",
                Integer.class, SecurityAuditEventType.ACCOUNT_DELETION_COMPLETED.name(), deleted.user().getId())).isZero();

        Fixture businessOwner = fixture("audit-business@example.com", ApplicationRole.OWNER, true);
        deletion.deleteBusiness(businessOwner.user().getId(), businessOwner.business().getId(), PASSWORD);
        assertThat(auditCount(SecurityAuditEventType.BUSINESS_DELETION_COMPLETED, null)).isEqualTo(1);
        assertThat(auditReason(SecurityAuditEventType.BUSINESS_DELETION_COMPLETED, null))
                .isEqualTo("BUSINESS_ID_" + businessOwner.business().getId());
        assertThat(jdbc.queryForObject("select count(*) from public.security_audit_events where event_type = ? and (client_ip is not null or device_id is not null)",
                Integer.class, SecurityAuditEventType.BUSINESS_DELETION_COMPLETED.name())).isZero();
    }

    @Test
    void concurrentAccountAndBusinessDeletionUseTheSameUserThenBusinessOrderWithoutPartialState() throws Exception {
        Fixture fixture = fixture("concurrent-destructive-owner@example.com", ApplicationRole.OWNER, true);
        openAndRotate(fixture, "concurrent-destructive-session");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DestructiveResult> account = executor.submit(() -> destructiveAtBarrier(
                    ready, start, () -> deletion.confirmInApp(fixture.user().getId(), PASSWORD)));
            Future<DestructiveResult> business = executor.submit(() -> destructiveAtBarrier(
                    ready, start, () -> {
                        deletion.deleteBusiness(fixture.user().getId(), fixture.business().getId(), PASSWORD);
                        return AccountDeletionOutcome.COMPLETED;
                    }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(account.get(10, TimeUnit.SECONDS).unexpected()).isNull();
            assertThat(business.get(10, TimeUnit.SECONDS).unexpected()).isNull();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        AppUser account = users.findById(fixture.user().getId()).orElseThrow();
        boolean accountDeleted = account.getRegistrationState() == AccountRegistrationState.DELETED;
        boolean businessDeleted = businesses.findById(fixture.business().getId()).isEmpty();
        assertThat(accountDeleted || businessDeleted).isTrue();
        if (accountDeleted) {
            assertThat(memberships.findByUserIdOrderByIdAsc(account.getId())).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from public.auth_sessions where user_id = ?", Integer.class, account.getId()))
                    .isZero();
        }
        if (businessDeleted) {
            assertThat(jdbc.queryForObject("select count(*) from public.auth_sessions s join public.business_memberships m on m.id = s.active_membership_id where m.business_id = ?", Integer.class, fixture.business().getId()))
                    .isZero();
        }
    }

    private void requestAtBarrier(String email, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent account-deletion requests were not released");
            }
            deletion.requestPublic(new AccountDeletionRequestInput(email), "198.51.100.40");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent account-deletion request was interrupted", exception);
        }
    }

    private DestructiveResult destructiveAtBarrier(CountDownLatch ready,
                                                    CountDownLatch start,
                                                    java.util.concurrent.Callable<AccountDeletionOutcome> action) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent destructive operations were not released");
            }
            return new DestructiveResult(action.call(), null);
        } catch (SecurityApiException expectedDomainOutcome) {
            // The operation that runs second may find that the first safely
            // removed its membership. That is a serialized domain outcome,
            // never a database deadlock or partial commit.
            return new DestructiveResult(null, null);
        } catch (Exception exception) {
            return new DestructiveResult(null, exception);
        }
    }

    private Fixture fixture(String email, ApplicationRole role, boolean alternateOwner) {
        AppUser user = activeUser(email);
        Business business = businesses.saveAndFlush(Business.create("Business " + email, NOW));
        BusinessMembership membership = memberships.saveAndFlush(BusinessMembership.create(user, business, role, NOW));
        if (alternateOwner) {
            AppUser owner = activeUser("alternate-" + email);
            memberships.saveAndFlush(BusinessMembership.create(owner, business, ApplicationRole.OWNER, NOW));
        }
        return new Fixture(user, business, membership);
    }

    private AppUser activeUser(String email) {
        AppUser user = AppUser.create(email, "User " + email, passwords.encode(PASSWORD), ApplicationRole.OWNER, NOW);
        user.setNormalizedEmail(email, AccountRegistrationState.ACTIVE, NOW);
        return users.saveAndFlush(user);
    }

    private RequestToken pending(AppUser user, AccountDeletionSource source) {
        EmailVerificationTokenGenerator.TokenMaterial material = tokens.generate();
        AccountDeletionRequest request = requests.saveAndFlush(AccountDeletionRequest.pending(
                user, source, material.hash(), NOW, NOW.plus(Duration.ofHours(1))));
        return new RequestToken(request.getId(), material.plaintext());
    }

    private AccountDeletionRequest onlyRequest(AppUser user) {
        return requests.findAll().stream().filter(request -> request.getUser().getId().equals(user.getId()))
                .findFirst().orElseThrow();
    }

    private int auditCount(SecurityAuditEventType eventType, Long subjectUserId) {
        if (subjectUserId == null) {
            return jdbc.queryForObject("select count(*) from public.security_audit_events where event_type = ?",
                    Integer.class, eventType.name());
        }
        return jdbc.queryForObject("select count(*) from public.security_audit_events where event_type = ? and subject_user_id = ?",
                Integer.class, eventType.name(), subjectUserId);
    }

    private String auditReason(SecurityAuditEventType eventType, Long subjectUserId) {
        if (subjectUserId == null) {
            return jdbc.queryForObject("select reason_code from public.security_audit_events where event_type = ?",
                    String.class, eventType.name());
        }
        return jdbc.queryForObject("select reason_code from public.security_audit_events where event_type = ? and subject_user_id = ?",
                String.class, eventType.name(), subjectUserId);
    }

    private AuthSessionService.SessionToken openAndRotate(Fixture fixture, String deviceId) {
        return openAndRotate(fixture.user(), fixture.business(), deviceId);
    }

    private AuthSessionService.SessionToken openAndRotate(AppUser user, Business business, String deviceId) {
        AuthSessionService.SessionToken opened = sessions.open(
                user.getId(), user.getPasswordHash(), deviceId, null, null, business.getId(), "198.51.100.41");
        sessions.rotate(opened.rawRefreshToken(), deviceId, "198.51.100.41");
        return opened;
    }

    private JwtRequestPostProcessor jwt(Fixture fixture, AuthSessionService.SessionToken session) {
        Instant expiresAt = NOW.plus(Duration.ofMinutes(15));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject(fixture.user().getId().toString())
                        .claim("sid", session.session().getId().toString())
                        .claim("mid", fixture.membership().getId().toString())
                        .claim("bid", fixture.business().getId().toString())
                        .claim("role", fixture.membership().getRole().name())
                        .claim("username", fixture.user().getUsername())
                        .issuedAt(NOW)
                        .expiresAt(expiresAt))
                .authorities(new SimpleGrantedAuthority("ROLE_" + fixture.membership().getRole().name()));
    }

    private record Fixture(AppUser user, Business business, BusinessMembership membership) { }
    private record RequestToken(java.util.UUID requestId, String plaintext) { }
    private record DestructiveResult(AccountDeletionOutcome outcome, Exception unexpected) { }

    static final class CapturingTransactionalEmailSender implements TransactionalEmailSender {
        private final List<TransactionalEmail> messages = new ArrayList<>();
        @Override public synchronized void send(TransactionalEmail email) {
            messages.add(email);
            notifyAll();
        }
        synchronized void clear() { messages.clear(); }
        synchronized List<String> tokens(int expectedCount) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (messages.size() < expectedCount && System.nanoTime() < deadline) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, deadline - System.nanoTime());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while awaiting account-deletion email delivery", exception);
                }
            }
            if (messages.size() < expectedCount) {
                throw new AssertionError("Expected " + expectedCount + " account-deletion email deliveries but saw " + messages.size());
            }
            return messages.stream().map(message -> message.textBody().substring(message.textBody().indexOf("#token=") + 7)
                    .lines().findFirst().orElseThrow()).toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean @Primary CapturingTransactionalEmailSender transactionalEmailSender() { return new CapturingTransactionalEmailSender(); }
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
