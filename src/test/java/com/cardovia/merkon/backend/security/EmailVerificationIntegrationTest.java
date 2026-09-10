package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, EmailVerificationIntegrationTest.TestInfrastructureConfiguration.class})
class EmailVerificationIntegrationTest {

    private static final String PASSWORD = "una frase larga segura email 2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private TermsAcceptanceRepository termsAcceptances;
    @Autowired private EmailVerificationTokenRepository verificationTokens;
    @Autowired private EmailVerificationTokenGenerator tokenGenerator;
    @Autowired private EmailVerificationService verification;
    @Autowired private CapturingTransactionalEmailSender sender;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @BeforeEach
    void cleanFixtures() {
        sender.clear();
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.email_verification_tokens");
        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
        jdbcTemplate.update("delete from public.user_terms_acceptances");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream()
                .filter(business -> business.getLegacyKey() == null)
                .forEach(businesses::delete);
    }

    @Test
    void registrationPersistsOnlyHashSendsThroughFakeAndVerificationActivatesTheSameOwnerBusiness() throws Exception {
        register("owner@example.com", "Owner", "Owner business")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Solicitud de registro aceptada."));

        AppUser pending = users.findByEmail("owner@example.com").orElseThrow();
        BusinessMembership membership = memberships.findByUserIdOrderByIdAsc(pending.getId()).get(0);
        EmailVerificationToken token = verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(pending.getId()).get(0);
        String plaintext = sender.singleToken();

        assertThat(token.getTokenHash()).isEqualTo(tokenGenerator.hash(plaintext));
        assertThat(token.getTokenHash()).isNotEqualTo(plaintext);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.email_verification_tokens where token_hash = ?",
                Integer.class, plaintext)).isZero();
        assertThat(sender.single().recipient()).isEqualTo("owner@example.com");
        assertThat(sender.single().textBody()).contains("MerkON").contains("Verifica tu correo");

        Long userId = pending.getId();
        Long businessId = membership.getBusiness().getId();
        Long membershipId = membership.getId();
        mockMvc.perform(post("/api/v1/registration/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EmailVerificationRequest(plaintext))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Correo verificado correctamente."));

        AppUser active = users.findById(userId).orElseThrow();
        assertThat(active.isActive()).isTrue();
        assertThat(active.getRegistrationState()).isEqualTo(AccountRegistrationState.ACTIVE);
        assertThat(active.getEmailVerifiedAt()).isNotNull();
        assertThat(verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(userId)).singleElement()
                .satisfies(consumed -> assertThat(consumed.getUsedAt()).isNotNull());
        BusinessMembership unchangedMembership = memberships.findByUserIdOrderByIdAsc(userId).get(0);
        assertThat(unchangedMembership.getId()).isEqualTo(membershipId);
        assertThat(unchangedMembership.getBusiness().getId()).isEqualTo(businessId);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                "owner@example.com", PASSWORD, "verified-device", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void invalidExpiredRevokedAndUsedTokensAreRejectedWithoutActivatingTheAccount() throws Exception {
        register("tokens@example.com", "Token owner", "Token business");
        AppUser user = users.findByEmail("tokens@example.com").orElseThrow();
        String firstToken = sender.singleToken();

        verify(firstToken + "altered").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_INVALID_TOKEN"));
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();

        resend("tokens@example.com").andExpect(status().isAccepted());
        String newestToken = sender.lastTokenAfter(2);
        verify(firstToken).andExpect(status().isBadRequest());

        jdbcTemplate.update("update public.email_verification_tokens "
                + "set created_at = current_timestamp - interval '2' day, expires_at = current_timestamp - interval '1' day "
                + "where token_hash = ?", tokenGenerator.hash(newestToken));
        verify(newestToken).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();

        resend("tokens@example.com").andExpect(status().isAccepted());
        String usableToken = sender.lastTokenAfter(3);
        verify(usableToken).andExpect(status().isOk());
        verify(usableToken).andExpect(status().isBadRequest());
    }

    @Test
    void senderFailureKeepsTheCommittedAccountPendingAndReturnsTheSameGenericRegistrationResponse() throws Exception {
        sender.fail = true;
        register("delivery-failure@example.com", "Delivery failure", "Delivery failure business")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Solicitud de registro aceptada."));

        AppUser user = users.findByEmail("delivery-failure@example.com").orElseThrow();
        sender.awaitAttempts(1);
        assertThat(user.isActive()).isFalse();
        assertThat(user.getRegistrationState()).isEqualTo(AccountRegistrationState.PENDING_EMAIL_VERIFICATION);
        assertThat(verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.security_audit_events
                where event_type = 'EMAIL_VERIFICATION_SEND_FAILED' and reason_code = 'TEST_DELIVERY_FAILURE'
                """, Integer.class)).isOne();
    }

    @Test
    void resendRevokesPriorTokenKeepsResponsesEnumerationSafeAndRateLimitsKnownAndUnknownIdentities() throws Exception {
        register("resend@example.com", "Resend owner", "Resend business");
        String firstToken = sender.singleToken();
        String pendingResponse = resend("resend@example.com").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String secondToken = sender.lastTokenAfter(2);
        assertThat(secondToken).isNotEqualTo(firstToken);
        List<EmailVerificationToken> tokens = verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(
                users.findByEmail("resend@example.com").orElseThrow().getId());
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).getRevokedAt()).isNotNull();
        assertThat(tokens.get(1).getRevokedAt()).isNull();
        verify(firstToken).andExpect(status().isBadRequest());
        verify(secondToken).andExpect(status().isOk());

        int sendsBeforeNoOpResends = sender.size();
        String activeResponse = resend("resend@example.com").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String unknownResponse = resend("missing@example.com").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(activeResponse).isEqualTo(pendingResponse).isEqualTo(unknownResponse);
        assertThat(sender.size()).isEqualTo(sendsBeforeNoOpResends);

        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
        for (int index = 1; index <= 5; index++) {
            resend("unknown-rate@example.com").andExpect(status().isAccepted());
        }
        resend("unknown-rate@example.com").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_RESEND_RATE_LIMITED"));
        assertThat(jdbcTemplate.queryForObject("select max(attempt_count) from public.registration_rate_limit_buckets",
                Integer.class)).isEqualTo(6);

        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
        register("known-rate@example.com", "Known rate", "Known rate business");
        for (int index = 1; index <= 5; index++) {
            resend("known-rate@example.com").andExpect(status().isAccepted());
        }
        resend("known-rate@example.com").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_RESEND_RATE_LIMITED"));
    }

    @Test
    void concurrentSameTokenVerificationConsumesItOnceAndConcurrentResendLeavesOneActiveToken() throws Exception {
        register("concurrent@example.com", "Concurrent owner", "Concurrent business");
        String token = sender.singleToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> verifyAtBarrier(token, ready, start)),
                    executor.submit(() -> verifyAtBarrier(token, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(results.stream().filter(this::result).count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        AppUser active = users.findByEmail("concurrent@example.com").orElseThrow();
        assertThat(active.isActive()).isTrue();
        assertThat(verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(active.getId())).singleElement()
                .satisfies(used -> assertThat(used.getUsedAt()).isNotNull());

        register("concurrent-resend@example.com", "Concurrent resend", "Concurrent resend business");
        CountDownLatch resendReady = new CountDownLatch(2);
        CountDownLatch resendStart = new CountDownLatch(1);
        ExecutorService resendExecutor = Executors.newFixedThreadPool(2);
        try {
            List<Future<EmailVerificationResponse>> resends = List.of(
                    resendExecutor.submit(() -> resendAtBarrier(resendReady, resendStart)),
                    resendExecutor.submit(() -> resendAtBarrier(resendReady, resendStart)));
            assertThat(resendReady.await(5, TimeUnit.SECONDS)).isTrue();
            resendStart.countDown();
            for (Future<EmailVerificationResponse> response : resends) {
                assertThat(response.get(10, TimeUnit.SECONDS).message())
                        .isEqualTo("Si la cuenta requiere verificación, enviaremos un nuevo correo.");
            }
        } finally {
            resendStart.countDown();
            resendExecutor.shutdownNow();
        }
        AppUser pending = users.findByEmail("concurrent-resend@example.com").orElseThrow();
        assertThat(verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(pending.getId()))
                .filteredOn(candidate -> candidate.getUsedAt() == null && candidate.getRevokedAt() == null)
                .hasSize(1);
    }

    @Test
    void verifyAndResendUseOneLockOrderAndLeaveOneCoherentOutcome() throws Exception {
        register("verify-resend-race@example.com", "Race owner", "Race business");
        String oldToken = sender.singleToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> verificationResult = executor.submit(() -> verifyAtBarrier(oldToken, ready, start));
            Future<EmailVerificationResponse> resendResult = executor.submit(() -> resendAtBarrier(
                    "verify-resend-race@example.com", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean verified = result(verificationResult);
            assertThat(resendResult.get(10, TimeUnit.SECONDS).message())
                    .isEqualTo(EmailVerificationResponse.resendAccepted().message());

            AppUser user = users.findByEmail("verify-resend-race@example.com").orElseThrow();
            List<EmailVerificationToken> tokens = verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId());
            long activeTokens = tokens.stream().filter(candidate -> candidate.getUsedAt() == null
                    && candidate.getRevokedAt() == null).count();
            assertThat(activeTokens).isLessThanOrEqualTo(1);
            if (verified) {
                assertThat(user.isActive()).isTrue();
                assertThat(activeTokens).isZero();
                assertThat(tokens).anySatisfy(candidate -> {
                    assertThat(candidate.getTokenHash()).isEqualTo(tokenGenerator.hash(oldToken));
                    assertThat(candidate.getUsedAt()).isNotNull();
                });
            } else {
                assertThat(user.isActive()).isFalse();
                assertThat(activeTokens).isEqualTo(1);
                assertThat(tokens).anySatisfy(candidate -> {
                    assertThat(candidate.getTokenHash()).isEqualTo(tokenGenerator.hash(oldToken));
                    assertThat(candidate.getRevokedAt()).isNotNull();
                });
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verificationClearsOnlyPreVerificationLockoutSoCorrectPasswordWorksImmediately() throws Exception {
        register("lockout@example.com", "Lockout owner", "Lockout business");
        String token = sender.singleToken();
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(
                                    "lockout@example.com", "incorrect password", "pending-device", null, null))))
                    .andExpect(status().isUnauthorized());
        }
        AppUser lockedPending = users.findByEmail("lockout@example.com").orElseThrow();
        assertThat(lockedPending.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(lockedPending.getLockedUntil()).isNotNull();

        verify(token).andExpect(status().isOk());

        AppUser active = users.findByEmail("lockout@example.com").orElseThrow();
        assertThat(active.isActive()).isTrue();
        assertThat(active.getFailedLoginAttempts()).isZero();
        assertThat(active.getLockedUntil()).isNull();
        assertThat(active.getLastLoginAt()).isNull();
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                "lockout@example.com", PASSWORD, "verified-device", null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void registrationAndPendingResendReturnBeforeTheProviderRoundTripCompletes() throws Exception {
        sender.blockNextDelivery();
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<Integer> registration = executor.submit(() -> register(
                    "dispatch-registration@example.com", "Dispatch owner", "Dispatch business")
                    .andReturn().getResponse().getStatus());
            assertThat(sender.awaitDeliveryStarted()).isTrue();
            assertThat(registration.get(2, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            sender.releaseBlockedDelivery();
            executor.shutdownNow();
        }

        sender.awaitAtLeast(1);
        sender.blockNextDelivery();
        ExecutorService resendExecutor = Executors.newFixedThreadPool(1);
        try {
            Future<Integer> resend = resendExecutor.submit(() -> resend("dispatch-registration@example.com")
                    .andReturn().getResponse().getStatus());
            assertThat(sender.awaitDeliveryStarted()).isTrue();
            assertThat(resend.get(2, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            sender.releaseBlockedDelivery();
            resendExecutor.shutdownNow();
        }
    }

    private boolean result(Future<Boolean> result) {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent verification did not finish", exception);
        }
    }

    private boolean verifyAtBarrier(String token, CountDownLatch ready, CountDownLatch start) {
        awaitBarrier(ready, start);
        try {
            verification.verify(new EmailVerificationRequest(token), "198.51.100.71");
            return true;
        } catch (SecurityApiException exception) {
            assertThat(exception.code()).isEqualTo("EMAIL_VERIFICATION_INVALID_TOKEN");
            return false;
        }
    }

    private EmailVerificationResponse resendAtBarrier(CountDownLatch ready, CountDownLatch start) {
        return resendAtBarrier("concurrent-resend@example.com", ready, start);
    }

    private EmailVerificationResponse resendAtBarrier(String email, CountDownLatch ready, CountDownLatch start) {
        awaitBarrier(ready, start);
        return verification.resend(new EmailVerificationResendRequest(email), "198.51.100.72");
    }

    private static void awaitBarrier(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent email-verification operation did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent email-verification operation was interrupted", exception);
        }
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String displayName, String businessName)
            throws Exception {
        return mockMvc.perform(post("/api/v1/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PublicRegistrationRequest(
                        email, displayName, PASSWORD, businessName, true))));
    }

    private org.springframework.test.web.servlet.ResultActions resend(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/registration/email-verification/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new EmailVerificationResendRequest(email))));
    }

    private org.springframework.test.web.servlet.ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/registration/email-verification/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new EmailVerificationRequest(token))));
    }

    static final class CapturingTransactionalEmailSender implements TransactionalEmailSender {
        private final List<TransactionalEmail> messages = new CopyOnWriteArrayList<>();
        private final Object monitor = new Object();
        private volatile boolean fail;
        private int attempts;
        private CountDownLatch deliveryStarted;
        private CountDownLatch releaseDelivery;

        @Override
        public void send(TransactionalEmail email) {
            CountDownLatch started;
            CountDownLatch release;
            synchronized (monitor) {
                attempts++;
                started = deliveryStarted;
                release = releaseDelivery;
                monitor.notifyAll();
            }
            if (started != null) {
                started.countDown();
            }
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new TransactionalEmailDeliveryException("TEST_DELIVERY_TIMEOUT");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new TransactionalEmailDeliveryException("TEST_DELIVERY_INTERRUPTED", exception);
                }
            }
            if (fail) {
                throw new TransactionalEmailDeliveryException("TEST_DELIVERY_FAILURE");
            }
            messages.add(email);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        void clear() {
            synchronized (monitor) {
                messages.clear();
                fail = false;
                attempts = 0;
                deliveryStarted = null;
                releaseDelivery = null;
                monitor.notifyAll();
            }
        }

        TransactionalEmail single() {
            awaitAtLeast(1);
            assertThat(messages).hasSize(1);
            return messages.get(0);
        }

        String singleToken() {
            return tokenFrom(single());
        }

        String lastTokenAfter(int expectedMessages) {
            awaitAtLeast(expectedMessages);
            return tokenFrom(messages.get(messages.size() - 1));
        }

        int size() {
            return messages.size();
        }

        void awaitAtLeast(int expectedMessages) {
            awaitCondition(() -> messages.size() >= expectedMessages, "email delivery did not complete");
        }

        void awaitAttempts(int expectedAttempts) {
            awaitCondition(() -> attempts >= expectedAttempts, "email delivery did not start");
        }

        void blockNextDelivery() {
            synchronized (monitor) {
                deliveryStarted = new CountDownLatch(1);
                releaseDelivery = new CountDownLatch(1);
            }
        }

        boolean awaitDeliveryStarted() throws InterruptedException {
            CountDownLatch started;
            synchronized (monitor) {
                started = deliveryStarted;
            }
            return started != null && started.await(5, TimeUnit.SECONDS);
        }

        void releaseBlockedDelivery() {
            CountDownLatch release;
            synchronized (monitor) {
                release = releaseDelivery;
                releaseDelivery = null;
                deliveryStarted = null;
            }
            if (release != null) {
                release.countDown();
            }
        }

        private void awaitCondition(java.util.function.BooleanSupplier condition, String message) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (!condition.getAsBoolean()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        throw new AssertionError(message);
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(message, exception);
                    }
                }
            }
        }

        private static String tokenFrom(TransactionalEmail email) {
            String line = email.textBody().lines().filter(value -> value.startsWith("https://"))
                    .findFirst().orElseThrow();
            return UriComponentsBuilder.fromUriString(line).build().getQueryParams().getFirst("token");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary CapturingTransactionalEmailSender transactionalEmailSender() {
            return new CapturingTransactionalEmailSender();
        }

        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
