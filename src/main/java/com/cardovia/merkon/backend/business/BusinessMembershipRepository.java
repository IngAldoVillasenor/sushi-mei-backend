package com.cardovia.merkon.backend.business;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusinessMembershipRepository extends JpaRepository<BusinessMembership, Long> {

    @Query("""
            select membership from BusinessMembership membership
            join fetch membership.user
            join fetch membership.business
            where membership.user.id = :userId
            order by membership.id asc
            """)
    List<BusinessMembership> findByUserIdOrderByIdAsc(@Param("userId") Long userId);

    @Query("""
            select membership from BusinessMembership membership
            join fetch membership.user
            join fetch membership.business
            where membership.user.id = :userId and membership.business.id = :businessId
            """)
    Optional<BusinessMembership> findByUserIdAndBusinessId(@Param("userId") Long userId,
                                                             @Param("businessId") Long businessId);

    @Query("""
            select membership from BusinessMembership membership
            join fetch membership.user
            join fetch membership.business
            where membership.business.id = :businessId
            order by membership.id asc
            """)
    List<BusinessMembership> findByBusinessIdOrderByIdAsc(@Param("businessId") Long businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from BusinessMembership membership
            join fetch membership.user user
            where membership.business.id = :businessId
              and membership.role = com.cardovia.merkon.backend.security.ApplicationRole.OWNER
              and user.active = true
            order by membership.id asc
            """)
    List<BusinessMembership> findActiveOwnersForBusinessForUpdate(@Param("businessId") Long businessId);

    @Query("select count(membership) from BusinessMembership membership join membership.user user where membership.business.id = :businessId and membership.role = com.cardovia.merkon.backend.security.ApplicationRole.OWNER and user.active = true")
    long countActiveOwnersForBusiness(@Param("businessId") Long businessId);

    long countByUserId(Long userId);
}
