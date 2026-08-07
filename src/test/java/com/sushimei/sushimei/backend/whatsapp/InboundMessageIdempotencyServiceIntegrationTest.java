package com.sushimei.sushimei.backend.whatsapp;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import(InboundMessageIdempotencyServiceIntegrationTest.TestInfrastructureConfiguration.class)
class InboundMessageIdempotencyServiceIntegrationTest {

    @Autowired
    private InboundMessageIdempotencyService inboundMessageIdempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearInboundMessages() {
        jdbcTemplate.update("delete from public.whatsapp_inbound_messages");
    }

    @Test
    void claimUsesThePrimaryKeyAsAnAtomicIdempotencyBoundary() {
        assertThat(inboundMessageIdempotencyService.claim("wamid-1", "525512345678", "text"))
                .isEqualTo(InboundMessageClaimOutcome.NEW);
        assertThat(inboundMessageIdempotencyService.claim("wamid-1", "525512345678", "text"))
                .isEqualTo(InboundMessageClaimOutcome.DUPLICATE);

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.whatsapp_inbound_messages", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select processing_status from public.whatsapp_inbound_messages", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject("select received_at from public.whatsapp_inbound_messages", Object.class))
                .isNotNull();
    }

    @Test
    void concurrentClaimsProduceExactlyOneNewWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<InboundMessageClaimOutcome> task = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return inboundMessageIdempotencyService.claim("wamid-concurrent", "525512345678", "text");
            };

            Future<InboundMessageClaimOutcome> first = executor.submit(task);
            Future<InboundMessageClaimOutcome> second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<InboundMessageClaimOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(outcomes).containsExactlyInAnyOrder(
                    InboundMessageClaimOutcome.NEW,
                    InboundMessageClaimOutcome.DUPLICATE
            );
            assertThat(jdbcTemplate.queryForObject("select count(*) from public.whatsapp_inbound_messages", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedAndFailedOutcomesAreRecordedInSeparateShortTransactions() {
        inboundMessageIdempotencyService.claim("wamid-complete", "525512345678", "text");
        inboundMessageIdempotencyService.markCompleted("wamid-complete");
        inboundMessageIdempotencyService.claim("wamid-failed", "525512345678", "image");
        inboundMessageIdempotencyService.markFailed("wamid-failed");

        assertThat(jdbcTemplate.queryForObject("""
                select processing_status
                from public.whatsapp_inbound_messages
                where message_id = 'wamid-complete'
                """, String.class)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
                select completed_at is not null
                from public.whatsapp_inbound_messages
                where message_id = 'wamid-complete'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select processing_status
                from public.whatsapp_inbound_messages
                where message_id = 'wamid-failed'
                """, String.class)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                select failed_at is not null
                from public.whatsapp_inbound_messages
                where message_id = 'wamid-failed'
                """, Boolean.class)).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
