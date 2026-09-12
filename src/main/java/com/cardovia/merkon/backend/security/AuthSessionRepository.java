package com.cardovia.merkon.backend.security;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import jakarta.persistence.LockModeType; import java.util.*;
public interface AuthSessionRepository extends JpaRepository<AuthSession,UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from AuthSession s join fetch s.user join fetch s.activeMembership membership join fetch membership.business where s.id=:id") Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);
 @Query("select s from AuthSession s join fetch s.user join fetch s.activeMembership membership join fetch membership.business where s.id=:id") Optional<AuthSession> findByIdWithContext(@Param("id") UUID id);
 @Query("select s from AuthSession s where s.user.id=:userId and s.deviceId=:deviceId and s.revokedAt is null") List<AuthSession> findActiveByUserAndDevice(@Param("userId") Long userId,@Param("deviceId") String deviceId);
 List<AuthSession> findByUserIdOrderByCreatedAtDesc(Long userId);
 List<AuthSession> findByUserIdAndRevokedAtIsNull(Long userId);
 @Query("select s from AuthSession s where s.user.id=:userId") List<AuthSession> findAllByUserId(@Param("userId") Long userId);
 @Query("select s from AuthSession s where s.activeMembership.business.id=:businessId") List<AuthSession> findAllByBusinessId(@Param("businessId") Long businessId);
 @Query("select s from AuthSession s join fetch s.activeMembership membership join fetch membership.business where s.user.id=:userId and membership.business.id=:businessId order by s.createdAt desc") List<AuthSession> findByUserIdAndBusinessIdOrderByCreatedAtDesc(@Param("userId") Long userId, @Param("businessId") Long businessId);
 @Query("select s from AuthSession s join fetch s.activeMembership membership where s.user.id=:userId and membership.business.id=:businessId and s.revokedAt is null") List<AuthSession> findActiveByUserIdAndBusinessId(@Param("userId") Long userId, @Param("businessId") Long businessId);
}
