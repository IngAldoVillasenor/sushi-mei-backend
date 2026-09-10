package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class RegistrationRateLimitTransaction {

    private static final String POSTGRESQL_RECORD_ATTEMPT = """
            insert into registration_rate_limit_buckets as bucket
                (bucket_key, window_started_at, attempt_count, updated_at)
            values (?, ?, 1, ?)
            on conflict (bucket_key) do update
            set window_started_at = case
                    when bucket.window_started_at <= ? then excluded.window_started_at
                    else bucket.window_started_at
                end,
                attempt_count = case
                    when bucket.window_started_at <= ? then 1
                    else bucket.attempt_count + 1
                end,
                updated_at = case
                    when bucket.updated_at >= excluded.updated_at then bucket.updated_at
                    else excluded.updated_at
                end
            returning attempt_count
            """;

    private static final String H2_RECORD_ATTEMPT = """
            merge into registration_rate_limit_buckets as bucket
            using (values (?, ?, ?)) as source(bucket_key, recorded_at, reset_before)
            on bucket.bucket_key = source.bucket_key
            when matched then update set
                window_started_at = case
                    when bucket.window_started_at <= source.reset_before then source.recorded_at
                    else bucket.window_started_at
                end,
                attempt_count = case
                    when bucket.window_started_at <= source.reset_before then 1
                    else bucket.attempt_count + 1
                end,
                updated_at = case
                    when bucket.updated_at >= source.recorded_at then bucket.updated_at
                    else source.recorded_at
                end
            when not matched then insert (bucket_key, window_started_at, attempt_count, updated_at)
                values (source.bucket_key, source.recorded_at, 1, source.recorded_at)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    RegistrationRateLimitTransaction(JdbcTemplate jdbcTemplate,
                                     Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Uses a committed independent transaction so denied attempts still count
     * and remain meaningful across Cloud Run instances sharing the database.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean recordAttempt(String bucketKey, int maximumAttempts, Duration window) {
        Instant now = clock.instant();
        Timestamp recordedAt = Timestamp.from(now);
        Timestamp resetBefore = Timestamp.from(now.minus(window));
        int attemptCount = switch (databaseDialect()) {
            case POSTGRESQL -> jdbcTemplate.queryForObject(
                    POSTGRESQL_RECORD_ATTEMPT,
                    Integer.class,
                    bucketKey,
                    recordedAt,
                    recordedAt,
                    resetBefore,
                    resetBefore);
            case H2 -> recordH2Attempt(bucketKey, recordedAt, resetBefore);
        };
        return attemptCount <= maximumAttempts;
    }

    private DatabaseDialect databaseDialect() {
        return jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
            String productName = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (productName.contains("postgresql")) {
                return DatabaseDialect.POSTGRESQL;
            }
            if (productName.contains("h2")) {
                return DatabaseDialect.H2;
            }
            throw new IllegalStateException("Unsupported registration rate-limit database: " + productName);
        });
    }

    private int recordH2Attempt(String bucketKey, Timestamp recordedAt, Timestamp resetBefore) {
        try {
            jdbcTemplate.update(H2_RECORD_ATTEMPT, bucketKey, recordedAt, resetBefore);
        } catch (DuplicateKeyException exception) {
            // H2's SQL-standard MERGE can race only while the first bucket row is
            // absent. The table has exactly one unique key (bucket_key), while all
            // other constraints are satisfied by this closed operation. Signal that
            // one narrowly-scoped creation race so the caller retries in a new tx.
            throw new BucketCreationRaceException(exception);
        }
        Integer currentAttemptCount = jdbcTemplate.queryForObject(
                "select attempt_count from registration_rate_limit_buckets where bucket_key = ?",
                Integer.class,
                bucketKey);
        if (currentAttemptCount == null) {
            throw new IllegalStateException("Rate-limit bucket was not persisted");
        }
        return currentAttemptCount;
    }

    private enum DatabaseDialect {
        POSTGRESQL,
        H2
    }

    static final class BucketCreationRaceException extends RuntimeException {
        BucketCreationRaceException(DuplicateKeyException cause) {
            super(cause);
        }
    }
}
