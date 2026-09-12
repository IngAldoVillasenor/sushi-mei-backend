package com.cardovia.merkon.backend.security;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest, UUID> {
 @Query("select r.user.id from AccountDeletionRequest r where r.tokenHash = :hash") Optional<Long> findUserIdByTokenHash(@Param("hash") String hash);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from AccountDeletionRequest r where r.tokenHash = :hash") Optional<AccountDeletionRequest> findByTokenHashForUpdate(@Param("hash") String hash);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from AccountDeletionRequest r where r.user.id = :userId and r.status = com.cardovia.merkon.backend.security.AccountDeletionStatus.PENDING_CONFIRMATION") List<AccountDeletionRequest> findPendingByUserIdForUpdate(@Param("userId") Long userId);
}
