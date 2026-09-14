package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Commit-level coverage for physical receipt cleanup after business deletion. */
@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, BusinessDeletionReceiptCleanupIntegrationTest.Infrastructure.class})
class BusinessDeletionReceiptCleanupIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final String PASSWORD = "receipt cleanup current password 2026";
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
    void clean() throws IOException {
        jdbc.update("delete from public.security_audit_events");
        jdbc.update("delete from public.conversation_sessions");
        jdbc.update("delete from public.orders");
        jdbc.update("delete from public.business_memberships");
        users.deleteAll();
        businesses.findAll().stream().filter(business -> business.getLegacyKey() == null).forEach(businesses::delete);
        try (var files = Files.list(RECEIPTS_ROOT)) {
            files.forEach(this::deleteQuietly);
        }
        jdbc.execute("drop table if exists public.business_deletion_receipt_rollback_guard");
    }

    @AfterEach
    void removeRollbackGuard() {
        jdbc.execute("drop table if exists public.business_deletion_receipt_rollback_guard");
    }

    @Test
    void committedBusinessDeletionDeletesOnlyTargetBusinessReceiptFilesAndDeduplicatesPaths() throws Exception {
        Fixture target = fixture("receipt-target@example.com", "Receipt target");
        Business other = businesses.saveAndFlush(Business.create("Receipt other", NOW));

        Path targetOrder = Files.writeString(RECEIPTS_ROOT.resolve("target-order.jpg"), "target-order");
        Path targetConversation = Files.writeString(RECEIPTS_ROOT.resolve("target-conversation.jpg"), "target-conversation");
        Path otherOrder = Files.writeString(RECEIPTS_ROOT.resolve("other-order.jpg"), "other-order");
        Path otherConversation = Files.writeString(RECEIPTS_ROOT.resolve("other-conversation.jpg"), "other-conversation");

        insertOrder(target.business(), target.user(), "target-order.jpg", "target-order");
        insertConversation(target.business(), "target-conversation", targetConversation.toString());
        // The second reference to targetOrder exercises database collection plus cleanup de-duplication.
        insertConversation(target.business(), "target-duplicate", "target-order.jpg");
        insertOrder(other, target.user(), otherOrder.toString(), "other-order");
        insertConversation(other, "other-conversation", "other-conversation.jpg");

        deletion.deleteBusiness(target.user().getId(), target.business().getId(), PASSWORD);

        assertThat(businesses.findById(target.business().getId())).isEmpty();
        assertThat(count("select count(*) from public.orders where business_id = ?", target.business().getId())).isZero();
        assertThat(count("select count(*) from public.conversation_sessions where business_id = ?", target.business().getId())).isZero();
        assertThat(targetOrder).doesNotExist();
        assertThat(targetConversation).doesNotExist();

        assertThat(businesses.findById(other.getId())).isPresent();
        assertThat(count("select count(*) from public.orders where business_id = ?", other.getId())).isOne();
        assertThat(count("select count(*) from public.conversation_sessions where business_id = ?", other.getId())).isOne();
        assertThat(otherOrder).exists();
        assertThat(otherConversation).exists();
        assertThat(count("select count(*) from public.security_audit_events where event_type = ?",
                SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED.name())).isZero();
    }

    @Test
    void rollbackNeverDeletesAReceiptFileBeforeTheDatabaseTransactionCommits() throws Exception {
        Fixture target = fixture("receipt-rollback@example.com", "Receipt rollback");
        Path receipt = Files.writeString(RECEIPTS_ROOT.resolve("rollback.jpg"), "rollback");
        insertOrder(target.business(), target.user(), "rollback.jpg", "rollback-order");
        jdbc.execute("""
                create table public.business_deletion_receipt_rollback_guard (
                    business_id bigint not null references public.businesses(id)
                )
                """);
        jdbc.update("insert into public.business_deletion_receipt_rollback_guard (business_id) values (?)", target.business().getId());

        assertThatThrownBy(() -> deletion.deleteBusiness(target.user().getId(), target.business().getId(), PASSWORD))
                .isInstanceOf(RuntimeException.class);

        assertThat(businesses.findById(target.business().getId())).isPresent();
        assertThat(count("select count(*) from public.orders where business_id = ?", target.business().getId())).isOne();
        assertThat(receipt).exists();
    }

    @Test
    void unsafeStoredPathCannotDeleteAFileOutsideTheConfiguredReceiptRoot() throws Exception {
        Fixture target = fixture("receipt-unsafe@example.com", "Receipt unsafe");
        Path outside = Files.writeString(Files.createTempFile("merkon-outside-receipt-", ".jpg"), "outside");
        insertOrder(target.business(), target.user(), outside.toString(), "unsafe-order");

        deletion.deleteBusiness(target.user().getId(), target.business().getId(), PASSWORD);

        assertThat(businesses.findById(target.business().getId())).isEmpty();
        assertThat(outside).exists();
        assertThat(count("select count(*) from public.security_audit_events where event_type = ? and reason_code = ? and client_ip is null and device_id is null",
                SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED.name(), "UNSAFE_RECEIPT_PATH_REJECTED")).isOne();
        assertThat(jdbc.queryForObject("select reason_code from public.security_audit_events where event_type = ?",
                String.class, SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED.name()))
                .doesNotContain(outside.toString());
        Files.deleteIfExists(outside);
    }

    @Test
    void directChildSymlinkIsRemovedWithoutDeletingItsExternalTargetWhenSupported() throws Exception {
        Fixture target = fixture("receipt-symlink@example.com", "Receipt symlink");
        Path outside = Files.writeString(Files.createTempFile("merkon-symlink-target-", ".jpg"), "outside");
        Path symlink = RECEIPTS_ROOT.resolve("symlink.jpg");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Files.deleteIfExists(outside);
            Assumptions.abort("Symbolic links are unavailable in this test environment");
            return;
        }
        insertOrder(target.business(), target.user(), "symlink.jpg", "symlink-order");

        deletion.deleteBusiness(target.user().getId(), target.business().getId(), PASSWORD);

        assertThat(symlink).doesNotExist();
        assertThat(outside).exists();
        Files.deleteIfExists(outside);
    }

    private Fixture fixture(String email, String name) {
        AppUser user = AppUser.create(email, name, passwords.encode(PASSWORD), ApplicationRole.OWNER, NOW);
        user.setNormalizedEmail(email, AccountRegistrationState.ACTIVE, NOW);
        user = users.saveAndFlush(user);
        Business business = businesses.saveAndFlush(Business.create(name + " business", NOW));
        BusinessMembership membership = memberships.saveAndFlush(BusinessMembership.create(user, business, ApplicationRole.OWNER, NOW));
        return new Fixture(user, business, membership);
    }

    private void insertOrder(Business business, AppUser user, String receiptPath, String label) {
        jdbc.update("""
                insert into public.orders (business_id, total_amount, total_amount_amount, created_at, phone_number, status,
                    order_source, client_request_id, created_by_user_id, request_fingerprint, payment_timing, transfer_receipt_path)
                values (?, 10.00, 10.00, current_timestamp, ?, 'PENDING', 'COUNTER', ?, ?, ?, 'IMMEDIATE', ?)
                """, business.getId(), "555" + label, UUID.randomUUID(), user.getId(), "a".repeat(64), receiptPath);
    }

    private void insertConversation(Business business, String label, String receiptPath) {
        jdbc.update("""
                insert into public.conversation_sessions (business_id, phone_number, created_at, last_activity_at, state,
                    transfer_receipt_path, updated_at, version)
                values (?, ?, current_timestamp, current_timestamp, 'ORDERING', ?, current_timestamp, 0)
                """, business.getId(), "556" + label, receiptPath);
    }

    private int count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Integer.class, values);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not clean isolated receipt fixture", exception);
        }
    }

    private static Path createReceiptsRoot() {
        try {
            return Files.createTempDirectory("merkon-business-deletion-receipts-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create isolated receipt fixture directory", exception);
        }
    }

    private record Fixture(AppUser user, Business business, BusinessMembership membership) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
