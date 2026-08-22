package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.BusinessDayOperationLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BusinessDayOperationLockRepository extends JpaRepository<BusinessDayOperationLock, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operationLock from BusinessDayOperationLock operationLock where operationLock.lockKey = 1")
    Optional<BusinessDayOperationLock> findSingletonForUpdate();
}
