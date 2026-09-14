package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.configuration.StorageProperties;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A post-commit filesystem failure cannot undo the completed database deletion. */
@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, BusinessDeletionReceiptCleanupFailureIntegrationTest.Infrastructure.class})
class BusinessDeletionReceiptCleanupFailureIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final String PASSWORD = "receipt failure current password 2026";
    private static final Path RECEIPTS_ROOT = createReceiptsRoot();

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.receipts-directory", () -> RECEIPTS_ROOT.toString());
    }

    @Autowired private AccountDeletionService deletion;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from public.security_audit_events");
        jdbc.update("delete from public.orders");
        jdbc.update("delete from public.business_memberships");
        users.deleteAll();
        businesses.findAll().stream().filter(business -> business.getLegacyKey() == null).forEach(businesses::delete);
    }

    @Test
    void receiptCleanupFailureLeavesTheBusinessDeletionCommittedAndAudited() throws Exception {
        AppUser user = AppUser.create("receipt-failure@example.com", "Receipt failure", passwords.encode(PASSWORD), ApplicationRole.OWNER, NOW);
        user.setNormalizedEmail("receipt-failure@example.com", AccountRegistrationState.ACTIVE, NOW);
        user = users.saveAndFlush(user);
        Business business = businesses.saveAndFlush(Business.create("Receipt failure business", NOW));
        memberships.saveAndFlush(BusinessMembership.create(user, business, ApplicationRole.OWNER, NOW));
        Files.writeString(RECEIPTS_ROOT.resolve("will-remain.jpg"), "receipt");
        jdbc.update("""
                insert into public.orders (business_id, total_amount, total_amount_amount, created_at, phone_number, status,
                    order_source, client_request_id, created_by_user_id, request_fingerprint, payment_timing, transfer_receipt_path)
                values (?, 10.00, 10.00, current_timestamp, '555-receipt-failure', 'PENDING', 'COUNTER', ?, ?, ?, 'IMMEDIATE', 'will-remain.jpg')
                """, business.getId(), UUID.randomUUID(), user.getId(), "a".repeat(64));

        deletion.deleteBusiness(user.getId(), business.getId(), PASSWORD);

        assertThat(businesses.findById(business.getId())).isEmpty();
        assertThat(RECEIPTS_ROOT.resolve("will-remain.jpg")).exists();
        assertThat(count("select count(*) from public.security_audit_events where event_type = ?",
                SecurityAuditEventType.BUSINESS_DELETION_COMPLETED.name())).isOne();
        assertThat(count("select count(*) from public.security_audit_events where event_type = ? and reason_code = ? and client_ip is null and device_id is null",
                SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED.name(), "RECEIPT_FILE_DELETE_FAILED")).isOne();
    }

    private int count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Integer.class, values);
    }

    private static Path createReceiptsRoot() {
        try {
            return Files.createTempDirectory("merkon-business-deletion-receipt-failure-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create isolated receipt fixture directory", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean @Primary ReceiptFileCleanupService failingReceiptFileCleanupService(StorageProperties storage,
                                                                                   SecurityAuditService audit) {
            return new ReceiptFileCleanupService(storage, audit,
                    path -> { throw new IOException("deterministic cleanup failure"); });
        }
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
