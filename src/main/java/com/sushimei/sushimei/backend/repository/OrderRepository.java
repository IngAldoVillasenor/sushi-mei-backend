package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.OrderRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderRecord, Long> {
    OrderRecord findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(String phoneNumber, String status);

    List<OrderRecord> findByStatusOrderByCreatedAtAsc(String status);

    List<OrderRecord> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    List<OrderRecord> findByStatusInOrderByCreatedAtAscIdAsc(List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orderRecord from OrderRecord orderRecord where orderRecord.id = :id")
    Optional<OrderRecord> findByIdForUpdate(@Param("id") Long id);

    Optional<OrderRecord> findBySourceCartId(Long sourceCartId);

    Optional<OrderRecord> findByClientRequestId(UUID clientRequestId);
}
