package com.cardovia.merkon.backend.business;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    java.util.Optional<Business> findByLegacyKey(String legacyKey);

    java.util.Optional<Business> findByIdAndActiveTrue(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select business from Business business where business.id = :id")
    Optional<Business> findByIdForBusinessDayOperations(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select business from Business business where business.id = :id")
    Optional<Business> findByIdForUpdate(@Param("id") Long id);

    List<Business> findByActiveTrueOrderByIdAsc();
}
