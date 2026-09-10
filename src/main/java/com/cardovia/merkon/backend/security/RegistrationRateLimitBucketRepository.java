package com.cardovia.merkon.backend.security;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationRateLimitBucketRepository extends JpaRepository<RegistrationRateLimitBucket, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bucket from RegistrationRateLimitBucket bucket where bucket.bucketKey = :bucketKey")
    Optional<RegistrationRateLimitBucket> findByBucketKeyForUpdate(@Param("bucketKey") String bucketKey);
}
