package com.cardovia.merkon.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Database-backed registration abuse-control state keyed by a privacy-safe hash. */
@Entity
@Table(name = "registration_rate_limit_buckets")
public class RegistrationRateLimitBucket {

    @Id
    @Column(name = "bucket_key", length = 64)
    private String bucketKey;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegistrationRateLimitBucket() {
    }

    static RegistrationRateLimitBucket create(String bucketKey, Instant now) {
        RegistrationRateLimitBucket bucket = new RegistrationRateLimitBucket();
        bucket.bucketKey = Objects.requireNonNull(bucketKey);
        bucket.windowStartedAt = Objects.requireNonNull(now);
        bucket.updatedAt = now;
        bucket.attemptCount = 1;
        return bucket;
    }

    boolean record(Instant now, Duration window, int maximumAttempts) {
        if (!now.isBefore(windowStartedAt.plus(window))) {
            windowStartedAt = now;
            attemptCount = 1;
        } else {
            attemptCount++;
        }
        updatedAt = now;
        return attemptCount <= maximumAttempts;
    }

    public String getBucketKey() { return bucketKey; }
    public Instant getWindowStartedAt() { return windowStartedAt; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getUpdatedAt() { return updatedAt; }
}
