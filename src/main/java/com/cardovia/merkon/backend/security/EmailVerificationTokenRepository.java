package com.cardovia.merkon.backend.security;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    @Query("select token.user.id from EmailVerificationToken token where token.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from EmailVerificationToken token where token.tokenHash = :tokenHash")
    Optional<EmailVerificationToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token from EmailVerificationToken token
            where token.user.id = :userId and token.usedAt is null and token.revokedAt is null
            order by token.createdAt asc, token.id asc
            """)
    List<EmailVerificationToken> findActiveByUserIdForUpdate(@Param("userId") Long userId);

    List<EmailVerificationToken> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);
}
