package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * V3-B coverage for the post-commit account-deletion email boundary and its
 * anonymous static-page security contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, AccountDeletionDeliveryIntegrationTest.TestInfrastructureConfiguration.class})
class AccountDeletionDeliveryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-12T13:00:00Z");
    private static final String PASSWORD = "una frase larga segura delivery 2026";

    @Autowired private AccountDeletionService deletion;
    @Autowired private AccountDeletionRequestTransaction requestTransaction;
    @Autowired private AccountDeletionDeliveryService delivery;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private BusinessRepository businesses;
    @Autowired private AccountDeletionRequestRepository requests;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private MockMvc mockMvc;
    @Autowired private RecordingTransactionalEmailSender sender;
    @Autowired private FailingAuditService audit;

    @BeforeEach
    void clean() {
        sender.reset();
        audit.allowAll();
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

    @AfterEach
    void releaseAnyBlockedDelivery() {
        sender.release();
    }

    @Test
    void publicRequestCommitsBeforeItsAsyncDeliveryAndRecordsMinimalSuccessAudits() throws Exception {
        AppUser user = activeUser("delivery-commit@example.com");
        sender.blockNextDelivery();
        audit.expectDeliveryAudit();

        mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"delivery-commit@example.com\"}"))
                .andExpect(status().isAccepted());

        assertThat(requests.findAll()).hasSize(1);
        assertThat(sender.awaitStarted()).isTrue();
        assertThat(sender.durableRequestsVisibleToDelivery()).isEqualTo(1);
        sender.release();
        assertThat(sender.awaitFinished()).isTrue();
        assertThat(audit.awaitDeliveryAudit()).isTrue();
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_REQUEST_ACCEPTED, user.getId())).isEqualTo(1);
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED, user.getId())).isEqualTo(1);
        assertThat(auditReason(SecurityAuditEventType.ACCOUNT_DELETION_REQUEST_ACCEPTED, user.getId())).isNull();
        assertThat(auditReason(SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED, user.getId())).isNull();
        assertThat(sender.messages()).singleElement().satisfies(email -> {
            assertThat(email.textBody()).contains("https://account-deletion.merkon.invalid/account-deletion#token=");
            assertThat(email.textBody()).contains("Abrir el enlace no elimina nada");
            assertThat(email.textBody()).contains("confirmación explícita");
            assertThat(email.textBody()).contains("vence aproximadamente en 60 minuto(s)");
            assertThat(email.htmlBody()).contains("confirmación explícita");
            assertThat(email.htmlBody()).contains("vence aproximadamente en 60 minuto(s)");
        });
        assertThat(auditSensitiveMetadataCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED, user.getId())).isZero();
    }

    @Test
    void rollbackDuringDurableRequestCreationDoesNotDispatchEmailOrPersistARequest() {
        activeUser("delivery-rollback@example.com");
        audit.failAcceptedRequests();

        assertThatThrownBy(() -> deletion.requestPublic(
                new AccountDeletionRequestInput("delivery-rollback@example.com"), "198.51.100.20"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TEST_ACCOUNT_DELETION_AUDIT_FAILURE");

        assertThat(requests.findAll()).isEmpty();
        assertThat(sender.attemptCount()).isZero();
    }

    @Test
    void providerFailureAfterCommitKeepsTheGenericResponseAndDurableRequestWhileRecordingSafeFailure() throws Exception {
        AppUser user = activeUser("delivery-failure@example.com");
        sender.failWithProviderError();
        audit.expectDeliveryAudit();

        mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"delivery-failure@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{\"message\":\"Solicitud de eliminación aceptada.\"}", false));

        assertThat(sender.awaitFinished()).isTrue();
        assertThat(audit.awaitDeliveryAudit()).isTrue();
        assertThat(requests.findAll()).hasSize(1);
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_REQUEST_ACCEPTED, user.getId())).isEqualTo(1);
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED, user.getId())).isEqualTo(1);
        assertThat(auditReason(SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED, user.getId()))
                .isEqualTo("TEST_PROVIDER_FAILURE");
        assertThat(auditSensitiveMetadataCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED, user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from public.security_audit_events where reason_code like ?", Integer.class,
                "%delivery-failure@example.com%")).isZero();
    }

    @Test
    void asynchronousDeliveryCannotRestoreIpMetadataAfterAccountDeletionCompletes() throws Exception {
        AppUser user = activeUser("delivery-deletion-race@example.com");
        sender.blockNextDelivery();
        audit.expectDeliveryAudit();

        deletion.requestPublic(new AccountDeletionRequestInput("delivery-deletion-race@example.com"), "198.51.100.77");
        assertThat(sender.awaitStarted()).isTrue();
        String plaintextToken = tokenFrom(sender.messages().get(0));

        assertThat(deletion.confirmPublic(new AccountDeletionTokenInput(plaintextToken)))
                .isEqualTo(AccountDeletionOutcome.COMPLETED);
        assertThat(users.findById(user.getId()).orElseThrow().getRegistrationState())
                .isEqualTo(AccountRegistrationState.DELETED);
        assertThat(jdbc.queryForObject("select count(*) from public.security_audit_events where subject_user_id = ? and (client_ip is not null or device_id is not null)",
                Integer.class, user.getId())).isZero();

        sender.release();
        assertThat(sender.awaitFinished()).isTrue();
        assertThat(audit.awaitDeliveryAudit()).isTrue();
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED, user.getId())).isEqualTo(1);
        assertThat(auditSensitiveMetadataCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED, user.getId())).isZero();
    }

    @Test
    void rejectedAsyncDispatchKeepsTheDurableRequestAndWritesAnIpFreeFailureAudit() throws Exception {
        AppUser user = activeUser("delivery-rejected@example.com");
        EmailVerificationTokenGenerator.TokenMaterial material = tokens.generate();
        AccountDeletionRequestTransaction.IssuedRequest issued = requestTransaction.issueForExistingAccount(
                "delivery-rejected@example.com", material.hash(), "198.51.100.78").orElseThrow();
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("TEST_REJECTED");
        };
        audit.expectDeliveryAudit();

        new AccountDeletionDeliveryDispatcher(rejectingExecutor, delivery).dispatch(
                issued.userId(), issued.recipient(), issued.requestId(), issued.expiresAt(), material.plaintext());

        assertThat(audit.awaitDeliveryAudit()).isTrue();
        assertThat(requests.findById(issued.requestId())).isPresent();
        assertThat(sender.attemptCount()).isZero();
        assertThat(auditCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED, user.getId())).isEqualTo(1);
        assertThat(auditReason(SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED, user.getId()))
                .isEqualTo("DELIVERY_DISPATCH_REJECTED");
        assertThat(auditSensitiveMetadataCount(SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED, user.getId())).isZero();
    }

    @Test
    void publicPageAndOnlyTheIntendedPublicDeletionRoutesAreAnonymous() throws Exception {
        mockMvc.perform(get("/account-deletion"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MerkON")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Cardovia")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ACCOUNT_DELETION_LAST_OWNER_ACTION_REQUIRED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ACCOUNT_DELETION_ACTION_REQUIRED")));

        String unknown = mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-deletion@example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String deleted = mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"another-unknown-deletion@example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(deleted).isEqualTo(unknown);

        mockMvc.perform(post("/api/v1/account-deletion/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/auth/account-deletion/impact")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/business-deletion/impact")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicRequestKeepsTheSameAcceptedContractForKnownInactiveDeletedAndUnknownIdentities() throws Exception {
        activeUser("public-known@example.com");
        AppUser inactive = activeUser("public-inactive@example.com");
        inactive.updateLegacyAdministrativeState(inactive.getDisplayName(), ApplicationRole.OWNER, false, NOW);
        users.saveAndFlush(inactive);
        AppUser deleted = activeUser("public-deleted@example.com");
        deleted.anonymizeDeleted("deleted-" + UUID.randomUUID(), passwords.encode(UUID.randomUUID().toString()), NOW);
        users.saveAndFlush(deleted);
        sender.expectDeliveries(2);

        String known = requestResponse("public-known@example.com");
        assertThat(requestResponse("public-inactive@example.com")).isEqualTo(known);
        assertThat(requestResponse("public-deleted@example.com")).isEqualTo(known);
        assertThat(requestResponse("public-unknown@example.com")).isEqualTo(known);
        assertThat(sender.awaitFinished()).isTrue();
    }

    private String requestResponse(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private AppUser activeUser(String email) {
        AppUser user = AppUser.create(email, "User " + email, passwords.encode(PASSWORD), ApplicationRole.OWNER, NOW);
        user.setNormalizedEmail(email, AccountRegistrationState.ACTIVE, NOW);
        return users.saveAndFlush(user);
    }

    private int auditCount(SecurityAuditEventType eventType, Long subjectUserId) {
        return jdbc.queryForObject("select count(*) from public.security_audit_events where event_type = ? and subject_user_id = ?",
                Integer.class, eventType.name(), subjectUserId);
    }

    private String auditReason(SecurityAuditEventType eventType, Long subjectUserId) {
        return jdbc.queryForObject("select reason_code from public.security_audit_events where event_type = ? and subject_user_id = ?",
                String.class, eventType.name(), subjectUserId);
    }

    private int auditSensitiveMetadataCount(SecurityAuditEventType eventType, Long subjectUserId) {
        return jdbc.queryForObject("select count(*) from public.security_audit_events where event_type = ? and subject_user_id = ? and (client_ip is not null or device_id is not null)",
                Integer.class, eventType.name(), subjectUserId);
    }

    private static String tokenFrom(TransactionalEmail email) {
        String text = email.textBody();
        int start = text.indexOf("#token=");
        return text.substring(start + "#token=".length()).lines().findFirst().orElseThrow();
    }

    static final class RecordingTransactionalEmailSender implements TransactionalEmailSender {
        private final JdbcTemplate jdbc;
        private final List<TransactionalEmail> messages = new ArrayList<>();
        private volatile boolean fail;
        private volatile CountDownLatch started = new CountDownLatch(0);
        private volatile CountDownLatch finished = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile int durableRequestsVisibleToDelivery;

        RecordingTransactionalEmailSender(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void send(TransactionalEmail email) {
            UUID requestId = UUID.fromString(email.idempotencyKey().substring("account-deletion-".length()));
            durableRequestsVisibleToDelivery = jdbc.queryForObject(
                    "select count(*) from public.account_deletion_requests where id = ?", Integer.class, requestId);
            synchronized (this) {
                messages.add(email);
            }
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for test delivery release");
                }
                if (fail) {
                    throw new TransactionalEmailDeliveryException("TEST_PROVIDER_FAILURE");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted during test delivery", exception);
            } finally {
                finished.countDown();
            }
        }

        void blockNextDelivery() {
            started = new CountDownLatch(1);
            finished = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void failWithProviderError() {
            fail = true;
            started = new CountDownLatch(1);
            finished = new CountDownLatch(1);
            release = new CountDownLatch(0);
        }

        void expectDeliveries(int count) {
            started = new CountDownLatch(count);
            finished = new CountDownLatch(count);
            release = new CountDownLatch(0);
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        boolean awaitFinished() throws InterruptedException {
            return finished.await(5, TimeUnit.SECONDS);
        }

        int durableRequestsVisibleToDelivery() {
            return durableRequestsVisibleToDelivery;
        }

        synchronized int attemptCount() {
            return messages.size();
        }

        synchronized List<TransactionalEmail> messages() {
            return List.copyOf(messages);
        }

        void release() {
            release.countDown();
        }

        synchronized void reset() {
            messages.clear();
            fail = false;
            durableRequestsVisibleToDelivery = 0;
            started = new CountDownLatch(0);
            finished = new CountDownLatch(0);
            release = new CountDownLatch(0);
        }
    }

    public static class FailingAuditService extends SecurityAuditService {
        private volatile boolean failRequestAccepted;
        private volatile CountDownLatch deliveryAudit = new CountDownLatch(0);

        FailingAuditService(SecurityAuditEventRepository events, Clock clock) {
            super(events, clock);
        }

        @Override
        public void record(SecurityAuditEventType eventType,
                           Long actorUserId,
                           Long subjectUserId,
                           UUID sessionId,
                           String deviceId,
                           String clientIp,
                           SecurityAuditOutcome outcome,
                           String reasonCode) {
            if (failRequestAccepted && eventType == SecurityAuditEventType.ACCOUNT_DELETION_REQUEST_ACCEPTED) {
                throw new IllegalStateException("TEST_ACCOUNT_DELETION_AUDIT_FAILURE");
            }
            super.record(eventType, actorUserId, subjectUserId, sessionId, deviceId, clientIp, outcome, reasonCode);
            if (eventType == SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED
                    || eventType == SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED) {
                deliveryAudit.countDown();
            }
        }

        void failAcceptedRequests() {
            failRequestAccepted = true;
        }

        void allowAll() {
            failRequestAccepted = false;
            deliveryAudit = new CountDownLatch(0);
        }

        void expectDeliveryAudit() {
            deliveryAudit = new CountDownLatch(1);
        }

        boolean awaitDeliveryAudit() throws InterruptedException {
            return deliveryAudit.await(5, TimeUnit.SECONDS);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean @Primary RecordingTransactionalEmailSender transactionalEmailSender(JdbcTemplate jdbc) {
            return new RecordingTransactionalEmailSender(jdbc);
        }
        @Bean @Primary FailingAuditService failingAuditService(SecurityAuditEventRepository events, Clock clock) {
            return new FailingAuditService(events, clock);
        }
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
