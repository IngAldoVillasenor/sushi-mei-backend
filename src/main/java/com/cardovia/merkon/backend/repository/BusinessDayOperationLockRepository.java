package com.cardovia.merkon.backend.repository;

import com.cardovia.merkon.backend.entity.BusinessDayOperationLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BusinessDayOperationLockRepository extends JpaRepository<BusinessDayOperationLock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operationLock from BusinessDayOperationLock operationLock where operationLock.businessId = :businessId")
    Optional<BusinessDayOperationLock> findByBusinessIdForUpdate(@org.springframework.data.repository.query.Param("businessId") Long businessId);
}
