package com.cardovia.merkon.backend.repository;

import com.cardovia.merkon.backend.businessday.BusinessDayStatus;
import com.cardovia.merkon.backend.entity.BusinessDay;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BusinessDayRepository extends JpaRepository<BusinessDay, Long> {

    Optional<BusinessDay> findByBusinessIdAndBusinessDate(Long businessId, LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select businessDay from BusinessDay businessDay where businessDay.business.id = :businessId and businessDay.businessDate = :businessDate")
    Optional<BusinessDay> findByBusinessIdAndBusinessDateForUpdate(
            @org.springframework.data.repository.query.Param("businessId") Long businessId,
            @org.springframework.data.repository.query.Param("businessDate") LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select businessDay from BusinessDay businessDay where businessDay.business.id = :businessId and businessDay.status = com.cardovia.merkon.backend.businessday.BusinessDayStatus.OPEN")
    Optional<BusinessDay> findOpenForUpdate(Long businessId);

    Optional<BusinessDay> findByBusinessIdAndStatus(Long businessId, BusinessDayStatus status);

    Optional<BusinessDay> findByBusinessDate(LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select businessDay from BusinessDay businessDay where businessDay.businessDate = :businessDate")
    Optional<BusinessDay> findByBusinessDateForUpdate(LocalDate businessDate);

    boolean existsByStatus(BusinessDayStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select businessDay from BusinessDay businessDay where businessDay.status = com.cardovia.merkon.backend.businessday.BusinessDayStatus.OPEN")
    Optional<BusinessDay> findOpenForUpdate();

    Optional<BusinessDay> findByStatus(BusinessDayStatus status);
}
