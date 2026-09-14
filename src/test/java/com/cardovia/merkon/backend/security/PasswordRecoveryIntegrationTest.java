package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
@Import({SecurityTestKeyConfiguration.class, PasswordRecoveryIntegrationTest.TestInfrastructureConfiguration.class})
class PasswordRecoveryIntegrationTest {

    private static final String ORIGINAL_PASSWORD = "una frase larga segura recovery 2026";
    private static final String RESET_PASSWORD = "otra frase segura recuperada 2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private PasswordResetTokenRepository resetTokens;
    @Autowired private EmailVerificationTokenGenerator tokenGenerator;
    @Autowired private PasswordPolicyService passwords;
    @Autowired private PasswordRecoveryService recovery;
    @Autowired private LoginAttemptService loginAttempts;
    @Autowired private AuthSessionService sessions;
    @Autowired private CapturingTransactionalEmailSender sender;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @BeforeEach
    void cleanFixtures() {
        sender.clear();
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.password_reset_tokens");
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
    void requestIsEnumerationSafeSendsOnlyForEligibleAccountAndPersistsOnlyHash() throws Exception {
        activeAccount("recover@example.com", "Recover owner", "Recover business");
        sender.clear();

        String knownBody = requestReset("  RECOVER@EXAMPLE.COM  ")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(PasswordRecoveryResponse.requestAccepted().message()))
                .andReturn().getResponse().getContentAsString();
        String plaintext = sender.singleToken();
        AppUser user = users.findByEmail("recover@example.com").orElseThrow();
        PasswordResetToken token = resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId()).get(0);

        assertThat(token.getTokenHash()).isEqualTo(tokenGenerator.hash(plaintext)).isNotEqualTo(plaintext);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.password_reset_tokens where token_hash = ?",
                Integer.class, plaintext)).isZero();
        assertThat(sender.single().recipient()).isEqualTo("recover@example.com");
        String emailText = sender.single().textBody();
        assertThat(emailText).contains("restablecer tu contrase");
        assertThat(emailText.replace("\u00f1", "\u00f1"))
                .contains("MerkON")
                .contains("restablecer tu contraseña")
                .contains("https://password-reset.merkon.invalid/reset-password?token=");
        assertThat(knownBody).doesNotContain(plaintext);

        int sendsBeforeUnknown = sender.size();
        String unknownBody = requestReset("missing@example.com")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(unknownBody).isEqualTo(knownBody);
        assertThat(sender.size()).isEqualTo(sendsBeforeUnknown);
    }

    @Test
    void confirmationChangesPasswordConsumesTokenAndRevokesExistingSessions() throws Exception {
        activeAccount("session-reset@example.com", "Session owner", "Session business");
        sender.clear();
        JsonNode login = login("session-reset@example.com", ORIGINAL_PASSWORD, "reset-device");
        String refreshToken = login.required("refreshToken").asText();

        requestReset("session-reset@example.com").andExpect(status().isAccepted());
        String token = sender.singleToken();
        Long userId = users.findByEmail("session-reset@example.com").orElseThrow().getId();

        confirm(token, RESET_PASSWORD).andExpect(status().isNoContent());
        PasswordResetToken consumed = resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(userId).get(0);
        assertThat(consumed.getUsedAt()).isNotNull();
        assertThat(passwords.matches(RESET_PASSWORD, users.findById(userId).orElseThrow().getPasswordHash())).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.auth_sessions where user_id = ? and revoked_at is null",
                Integer.class, userId)).isZero();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken, "reset-device"))))
                .andExpect(status().isUnauthorized());
        loginExpecting("session-reset@example.com", ORIGINAL_PASSWORD, "reset-old-password-device", 401);
        loginExpecting("session-reset@example.com", RESET_PASSWORD, "reset-new-password-device", 200);
        confirm(token, RESET_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_INVALID_TOKEN"));
    }

    @Test
    void oldPasswordEvaluationCannotOpenSessionAfterPasswordResetCommits() throws Exception {
        activeAccount("credential-epoch@example.com", "Credential owner", "Credential business");
        sender.clear();
        Long userId = users.findByEmail("credential-epoch@example.com").orElseThrow().getId();

        // This session exists before the reset and must be revoked by it.
        login("credential-epoch@example.com", ORIGINAL_PASSWORD, "pre-reset-session");
        LoginAttemptService.LoginEvaluation oldPasswordEvaluation = loginAttempts.evaluate(
                "credential-epoch@example.com", ORIGINAL_PASSWORD, "198.51.100.61");
        assertThat(oldPasswordEvaluation.success()).isTrue();

        requestReset("credential-epoch@example.com").andExpect(status().isAccepted());
        String token = sender.singleToken();
        CountDownLatch staleSessionTaskStarted = new CountDownLatch(1);
        CountDownLatch allowStaleSessionOpen = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> staleSession = executor.submit(() -> openStaleSessionAfterBarrier(
                    oldPasswordEvaluation, staleSessionTaskStarted, allowStaleSessionOpen));
            assertThat(staleSessionTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // The reset commits between successful OLD-password evaluation and
            // the separately transactional session-open operation.
            confirm(token, RESET_PASSWORD).andExpect(status().isNoContent());
            allowStaleSessionOpen.countDown();

            assertThat(result(staleSession)).isFalse();
        } finally {
            allowStaleSessionOpen.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.auth_sessions where user_id = ? and revoked_at is null",
                Integer.class, userId)).isZero();
        loginExpecting("credential-epoch@example.com", ORIGINAL_PASSWORD, "old-password-after-reset", 401);
        loginExpecting("credential-epoch@example.com", RESET_PASSWORD, "new-password-after-reset", 200);
    }

    @Test
    void pendingAndInactiveAccountsReceiveTheSameGenericRequestResponseWithoutResetEvidence() throws Exception {
        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PublicRegistrationRequest(
                                "pending-recovery@example.com", "Pending owner", ORIGINAL_PASSWORD,
                                "Pending business", true))))
                .andExpect(status().isAccepted());
        sender.single();
        sender.clear();

        String pendingResponse = requestReset("pending-recovery@example.com")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Long pendingUserId = users.findByEmail("pending-recovery@example.com").orElseThrow().getId();
        assertThat(resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(pendingUserId)).isEmpty();
        assertThat(sender.size()).isZero();

        activeAccount("inactive-recovery@example.com", "Inactive owner", "Inactive business");
        sender.clear();
        Long inactiveUserId = users.findByEmail("inactive-recovery@example.com").orElseThrow().getId();
        jdbcTemplate.update("update public.app_users set active = false where id = ?", inactiveUserId);
        String inactiveResponse = requestReset("inactive-recovery@example.com")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(inactiveUserId)).isEmpty();
        assertThat(sender.size()).isZero();

        String unknownResponse = requestReset("unknown-recovery@example.com")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(pendingResponse).isEqualTo(unknownResponse).isEqualTo(inactiveResponse);
    }

    @Test
    void tokenCannotResetPasswordAfterItsAccountBecomesInactive() throws Exception {
        activeAccount("deactivated-after-issue@example.com", "Deactivated owner", "Deactivated business");
        sender.clear();
        AppUser userBeforeDeactivation = users.findByEmail("deactivated-after-issue@example.com").orElseThrow();
        String originalHash = userBeforeDeactivation.getPasswordHash();

        requestReset("deactivated-after-issue@example.com").andExpect(status().isAccepted());
        String token = sender.singleToken();
        jdbcTemplate.update("update public.app_users set active = false where id = ?", userBeforeDeactivation.getId());

        confirm(token, RESET_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_INVALID_TOKEN"));
        AppUser userAfterAttempt = users.findById(userBeforeDeactivation.getId()).orElseThrow();
        assertThat(userAfterAttempt.getPasswordHash()).isEqualTo(originalHash);
        assertThat(resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(userBeforeDeactivation.getId())).singleElement()
                .satisfies(reset -> {
                    assertThat(reset.getUsedAt()).isNull();
                    assertThat(reset.getRevokedAt()).isNull();
                });
    }

    @Test
    void replacementExpiryAndInvalidPasswordDoNotLeaveAnInvalidPartialReset() throws Exception {
        activeAccount("lifecycle-reset@example.com", "Lifecycle owner", "Lifecycle business");
        sender.clear();

        requestReset("lifecycle-reset@example.com").andExpect(status().isAccepted());
        String firstToken = sender.singleToken();
        requestReset("lifecycle-reset@example.com").andExpect(status().isAccepted());
        String secondToken = sender.lastTokenAfter(2);
        AppUser user = users.findByEmail("lifecycle-reset@example.com").orElseThrow();
        List<PasswordResetToken> tokens = resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId());
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).getRevokedAt()).isNotNull();

        confirm(firstToken, RESET_PASSWORD).andExpect(status().isBadRequest());
        confirm(secondToken, "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_REJECTED"));
        assertThat(resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId()).get(1).getUsedAt()).isNull();
        confirm(secondToken, RESET_PASSWORD).andExpect(status().isNoContent());

        activeAccount("expired-reset@example.com", "Expired owner", "Expired business");
        sender.clear();
        requestReset("expired-reset@example.com").andExpect(status().isAccepted());
        String expiredToken = sender.singleToken();
        jdbcTemplate.update("update public.password_reset_tokens set created_at = current_timestamp - interval '2' day, "
                + "expires_at = current_timestamp - interval '1' day where token_hash = ?", tokenGenerator.hash(expiredToken));
        confirm(expiredToken, RESET_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_INVALID_TOKEN"));
        confirm("random-reset-token", RESET_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_INVALID_TOKEN"));
    }

    @Test
    void concurrentConfirmationConsumesExactlyOneTokenAndCannotChangePasswordTwice() throws Exception {
        activeAccount("concurrent-reset@example.com", "Concurrent owner", "Concurrent business");
        sender.clear();
        requestReset("concurrent-reset@example.com").andExpect(status().isAccepted());
        String token = sender.singleToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> confirmAtBarrier(token, ready, start)),
                    executor.submit(() -> confirmAtBarrier(token, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(results.stream().filter(this::result).count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        AppUser user = users.findByEmail("concurrent-reset@example.com").orElseThrow();
        assertThat(passwords.matches(RESET_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId())).singleElement()
                .satisfies(candidate -> assertThat(candidate.getUsedAt()).isNotNull());
    }

    @Test
    void requestUsesDurableIdentityRateLimitForKnownAndUnknownEmails() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            requestReset("unknown-rate@example.com").andExpect(status().isAccepted());
        }
        requestReset("unknown-rate@example.com").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_RATE_LIMITED"));
        assertThat(jdbcTemplate.queryForObject("select max(attempt_count) from public.registration_rate_limit_buckets",
                Integer.class)).isEqualTo(6);
    }

    private void activeAccount(String email, String displayName, String businessName) throws Exception {
        sender.clear();
        mockMvc.perform(post("/api/v1/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PublicRegistrationRequest(
                                email, displayName, ORIGINAL_PASSWORD, businessName, true))))
                .andExpect(status().isAccepted());
        String verificationToken = sender.singleToken();
        mockMvc.perform(post("/api/v1/registration/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EmailVerificationRequest(verificationToken))))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-recovery/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PasswordRecoveryRequest(email))));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-recovery/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PasswordRecoveryConfirmRequest(token, password))));
    }

    private JsonNode login(String username, String password, String deviceId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password, deviceId, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void loginExpecting(String username, String password, String deviceId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password, deviceId, null, null))))
                .andExpect(status().is(expectedStatus));
    }

    private boolean confirmAtBarrier(String token, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent password recovery confirmation did not start");
            }
            recovery.confirm(new PasswordRecoveryConfirmRequest(token, RESET_PASSWORD), "198.51.100.31");
            return true;
        } catch (SecurityApiException exception) {
            assertThat(exception.code()).isEqualTo("PASSWORD_RECOVERY_INVALID_TOKEN");
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent password recovery confirmation was interrupted", exception);
        }
    }

    private boolean openStaleSessionAfterBarrier(LoginAttemptService.LoginEvaluation evaluation,
                                                 CountDownLatch taskStarted,
                                                 CountDownLatch allowSessionOpen) {
        taskStarted.countDown();
        try {
            if (!allowSessionOpen.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Stale session open was not released after password reset");
            }
            sessions.open(
                    evaluation.userId(),
                    evaluation.passwordHashSnapshot(),
                    "stale-old-password-device",
                    null,
                    null,
                    null,
                    "198.51.100.61");
            return true;
        } catch (SecurityApiException exception) {
            assertThat(exception.code()).isEqualTo("AUTH_INVALID_CREDENTIALS");
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Stale session open was interrupted", exception);
        }
    }

    private boolean result(Future<Boolean> result) {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent password recovery confirmation did not finish", exception);
        }
    }

    static final class CapturingTransactionalEmailSender implements TransactionalEmailSender {
        private final List<TransactionalEmail> messages = new CopyOnWriteArrayList<>();
        private final Object monitor = new Object();

        @Override
        public void send(TransactionalEmail email) {
            messages.add(email);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        void clear() {
            messages.clear();
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

        private void awaitAtLeast(int expectedMessages) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (messages.size() < expectedMessages) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        throw new AssertionError("Transactional email was not dispatched");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Transactional email dispatch was interrupted", exception);
                    }
                }
            }
        }

        private static String tokenFrom(TransactionalEmail email) {
            String link = email.textBody().lines().filter(line -> line.startsWith("https://"))
                    .findFirst().orElseThrow();
            return UriComponentsBuilder.fromUriString(link).build().getQueryParams().getFirst("token");
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
