package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.configuration.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes only direct receipt files beneath the configured receipt root after
 * an already committed business deletion. Database paths are treated as
 * untrusted input: nested paths, traversal and paths outside the root are
 * rejected rather than followed.
 */
@Component
class ReceiptFileCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiptFileCleanupService.class);

    private final StorageProperties storage;
    private final SecurityAuditService audit;
    private final ReceiptFileDeleter deleter;

    @Autowired
    ReceiptFileCleanupService(StorageProperties storage, SecurityAuditService audit) {
        this(storage, audit, Files::deleteIfExists);
    }

    ReceiptFileCleanupService(StorageProperties storage, SecurityAuditService audit, ReceiptFileDeleter deleter) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.deleter = Objects.requireNonNull(deleter, "deleter");
    }

    void scheduleAfterCommit(Long actorUserId, Long businessId, Collection<String> candidatePaths) {
        Set<String> paths = candidatePaths.stream()
                .filter(Objects::nonNull)
                .filter(path -> !path.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (paths.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Receipt cleanup must be scheduled from an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupCommitted(actorUserId, businessId, paths);
            }
        });
    }

    void cleanupCommitted(Long actorUserId, Long businessId, Collection<String> candidatePaths) {
        Set<Path> receipts = new LinkedHashSet<>();
        for (String candidate : candidatePaths) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path receipt = safeReceiptPath(candidate);
            if (receipt == null) {
                recordFailure(actorUserId, businessId, "UNSAFE_RECEIPT_PATH_REJECTED");
                continue;
            }
            if (!isFileOrMissing(receipt)) {
                recordFailure(actorUserId, businessId, "UNSAFE_RECEIPT_PATH_REJECTED");
                continue;
            }
            receipts.add(receipt);
        }
        for (Path receipt : receipts) {
            try {
                deleter.deleteIfExists(receipt);
            } catch (IOException | SecurityException exception) {
                recordFailure(actorUserId, businessId, "RECEIPT_FILE_DELETE_FAILED");
            }
        }
    }

    private Path safeReceiptPath(String candidate) {
        final Path raw;
        try {
            raw = Path.of(candidate);
        } catch (InvalidPathException exception) {
            return null;
        }
        if (containsTraversal(raw)) {
            return null;
        }

        Path configuredRoot = storage.receiptsDirectory().toAbsolutePath().normalize();
        Path lexical = resolveAgainstConfiguredRoot(raw, configuredRoot);
        if (!lexical.startsWith(configuredRoot)
                || lexical.equals(configuredRoot)
                || lexical.getParent() == null
                || !lexical.getParent().equals(configuredRoot)) {
            return null;
        }

        try {
            if (!Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
                return lexical;
            }
            Path canonicalRoot = configuredRoot.toRealPath();
            if (!Files.isDirectory(canonicalRoot)) {
                return null;
            }
            Path canonicalDirectChild = canonicalRoot.resolve(lexical.getFileName()).normalize();
            return canonicalDirectChild.getParent().equals(canonicalRoot) ? canonicalDirectChild : null;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private static Path resolveAgainstConfiguredRoot(Path raw, Path configuredRoot) {
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        // The current writer returns receiptsDirectory.resolve(file).toString().
        // When receiptsDirectory itself was configured relatively, that stored
        // value is relative to the application working directory rather than
        // just a file name beneath the configured root.
        Path fromWorkingDirectory = Path.of("").toAbsolutePath().normalize().resolve(raw).normalize();
        if (isDirectChild(fromWorkingDirectory, configuredRoot)) {
            return fromWorkingDirectory;
        }
        return configuredRoot.resolve(raw).normalize();
    }

    private static boolean isDirectChild(Path candidate, Path root) {
        return candidate.startsWith(root)
                && !candidate.equals(root)
                && candidate.getParent() != null
                && candidate.getParent().equals(root);
    }

    private static boolean containsTraversal(Path path) {
        for (Path component : path) {
            if ("..".equals(component.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFileOrMissing(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path);
    }

    private void recordFailure(Long actorUserId, Long businessId, String reasonCode) {
        try {
            audit.recordPostCommit(
                    SecurityAuditEventType.BUSINESS_DELETION_RECEIPT_CLEANUP_FAILED,
                    actorUserId,
                    null,
                    null,
                    null,
                    null,
                    SecurityAuditOutcome.FAILURE,
                    reasonCode);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not record post-commit receipt cleanup failure category={}", reasonCode);
        }
        LOGGER.warn("Post-commit business receipt cleanup failed category={}", reasonCode);
    }

    @FunctionalInterface
    interface ReceiptFileDeleter {
        boolean deleteIfExists(Path path) throws IOException;
    }
}
