package com.sushimei.sushimei.backend.security;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import jakarta.persistence.LockModeType; import java.util.*;
public interface AuthSessionRepository extends JpaRepository<AuthSession,UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from AuthSession s join fetch s.user where s.id=:id") Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);
 @Query("select s from AuthSession s where s.user.id=:userId and s.deviceId=:deviceId and s.revokedAt is null") List<AuthSession> findActiveByUserAndDevice(@Param("userId") Long userId,@Param("deviceId") String deviceId);
 List<AuthSession> findByUserIdOrderByCreatedAtDesc(Long userId);
 List<AuthSession> findByUserIdAndRevokedAtIsNull(Long userId);
}