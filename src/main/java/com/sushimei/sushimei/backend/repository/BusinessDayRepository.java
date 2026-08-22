package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.businessday.BusinessDayStatus;
import com.sushimei.sushimei.backend.entity.BusinessDay;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BusinessDayRepository extends JpaRepository<BusinessDay, Long> {

    Optional<BusinessDay> findByBusinessDate(LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select businessDay from BusinessDay businessDay where businessDay.businessDate = :businessDate")
    Optional<BusinessDay> findByBusinessDateForUpdate(LocalDate businessDate);

    boolean existsByStatus(BusinessDayStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select businessDay from BusinessDay businessDay where businessDay.status = com.sushimei.sushimei.backend.businessday.BusinessDayStatus.OPEN")
    Optional<BusinessDay> findOpenForUpdate();

    Optional<BusinessDay> findByStatus(BusinessDayStatus status);
}
