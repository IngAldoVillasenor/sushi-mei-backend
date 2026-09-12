package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cardovia.merkon.backend.configuration.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiptFileCleanupServiceTest {

    @TempDir Path receiptsDirectory;

    @Test
    void deduplicatesContainedCandidatesBeforeDeletingThem() throws Exception {
        Path receipt = Files.writeString(receiptsDirectory.resolve("shared.jpg"), "receipt");
        AtomicInteger deletes = new AtomicInteger();
        ReceiptFileCleanupService cleanup = new ReceiptFileCleanupService(
                new StorageProperties(receiptsDirectory, receiptsDirectory), mock(SecurityAuditService.class), path -> {
                    deletes.incrementAndGet();
                    return Files.deleteIfExists(path);
                });

        cleanup.cleanupCommitted(11L, 22L, List.of("shared.jpg", "shared.jpg", receipt.toString()));

        assertThat(deletes).hasValue(1);
        assertThat(receipt).doesNotExist();
    }

    @Test
    void acceptsTheRelativePathProducedWhenTheConfiguredReceiptDirectoryIsRelative() throws Exception {
        Path relativeRoot = Path.of("target", "receipt-cleanup-relative-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(relativeRoot);
        Path receipt = Files.writeString(relativeRoot.resolve("relative.jpg"), "receipt");
        String storedPath = Path.of("").toAbsolutePath().normalize().relativize(receipt.toAbsolutePath()).toString();
        ReceiptFileCleanupService cleanup = new ReceiptFileCleanupService(
                new StorageProperties(Path.of("target").resolve(relativeRoot.getFileName()), receiptsDirectory),
                mock(SecurityAuditService.class), Files::deleteIfExists);

        cleanup.cleanupCommitted(11L, 22L, List.of(storedPath));

        assertThat(receipt).doesNotExist();
        Files.deleteIfExists(relativeRoot);
    }

    @Test
    void rejectsTraversalWithoutTouchingTheOutsideFileAndAuditsOnlyABoundedReason() throws Exception {
        Path outside = Files.writeString(receiptsDirectory.getParent().resolve("outside-receipt.jpg"), "outside");
        SecurityAuditService audit = mock(SecurityAuditService.class);
        ReceiptFileCleanupService cleanup = new ReceiptFileCleanupService(
                new StorageProperties(receiptsDirectory, receiptsDirectory), audit, Files::deleteIfExists);

        cleanup.cleanupCommitted(11L, 22L, List.of("../outside-receipt.jpg"));

        assertThat(outside).exists();
        verify(audit).recordPostCommit(
                eq(SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED),
                eq(11L),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(SecurityAuditOutcome.FAILURE),
                eq("UNSAFE_RECEIPT_PATH_REJECTED"));
        Files.deleteIfExists(outside);
    }

    @Test
    void refusesDirectoriesEvenWhenTheyAreDirectChildrenOfTheReceiptRoot() throws Exception {
        Path directory = Files.createDirectory(receiptsDirectory.resolve("not-a-receipt"));
        SecurityAuditService audit = mock(SecurityAuditService.class);
        ReceiptFileCleanupService cleanup = new ReceiptFileCleanupService(
                new StorageProperties(receiptsDirectory, receiptsDirectory), audit, Files::deleteIfExists);

        cleanup.cleanupCommitted(11L, 22L, List.of("not-a-receipt"));

        assertThat(directory).isDirectory();
        verify(audit).recordPostCommit(
                eq(SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED),
                eq(11L),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(SecurityAuditOutcome.FAILURE),
                eq("UNSAFE_RECEIPT_PATH_REJECTED"));
        Files.deleteIfExists(directory);
    }

    @Test
    void cleanupFailureIsAuditedWithoutThrowingOrRetainingPathMetadata() throws Exception {
        SecurityAuditService audit = mock(SecurityAuditService.class);
        ReceiptFileCleanupService cleanup = new ReceiptFileCleanupService(
                new StorageProperties(receiptsDirectory, receiptsDirectory), audit,
                path -> { throw new IOException("deterministic test failure"); });

        cleanup.cleanupCommitted(11L, 22L, List.of("receipt.jpg"));

        verify(audit).recordPostCommit(
                eq(SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED),
                eq(11L),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(SecurityAuditOutcome.FAILURE),
                eq("RECEIPT_FILE_DELETE_FAILED"));
    }
}
