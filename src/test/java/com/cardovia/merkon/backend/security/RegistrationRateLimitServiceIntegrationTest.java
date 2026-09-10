package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, RegistrationRateLimitServiceIntegrationTest.TestInfrastructureConfiguration.class})
class RegistrationRateLimitServiceIntegrationTest {

    private static final String IDENTITY = "concurrent-rate-limit@example.com";

    @Autowired private RegistrationRateLimitService rateLimit;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearBuckets() {
        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
    }

    @RepeatedTest(5)
    void concurrentCanonicalIdentityAttemptsAreDurablyCounted() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = List.of(
                    executor.submit(() -> recordAtBarrier(ready, start)),
                    executor.submit(() -> recordAtBarrier(ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        String bucketKey = RegistrationRateLimitService.bucketKey("merkon-registration-identity-v2:", IDENTITY);
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from public.registration_rate_limit_buckets where bucket_key = ?",
                Integer.class,
                bucketKey)).isEqualTo(2);
    }

    private void recordAtBarrier(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent rate-limit calls did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent rate-limit call was interrupted", exception);
        }
        rateLimit.checkCanonicalIdentity(IDENTITY);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
