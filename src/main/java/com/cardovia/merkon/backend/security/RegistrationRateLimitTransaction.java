package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class RegistrationRateLimitTransaction {

    private final RegistrationRateLimitBucketRepository buckets;
    private final PublicRegistrationProperties properties;
    private final Clock clock;

    RegistrationRateLimitTransaction(RegistrationRateLimitBucketRepository buckets,
                                     PublicRegistrationProperties properties,
                                     Clock clock) {
        this.buckets = buckets;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Uses a committed independent transaction so denied attempts still count
     * and remain meaningful across Cloud Run instances sharing the database.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean recordAttempt(String bucketKey, int maximumAttempts) {
        Instant now = clock.instant();
        RegistrationRateLimitBucket bucket = buckets.findByBucketKeyForUpdate(bucketKey).orElse(null);
        if (bucket == null) {
            buckets.saveAndFlush(RegistrationRateLimitBucket.create(bucketKey, now));
            return true;
        }
        return bucket.record(now, properties.rateLimitWindow(), maximumAttempts);
    }
}
