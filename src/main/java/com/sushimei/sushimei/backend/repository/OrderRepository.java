package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderRecord, Long> {
    OrderRecord findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(String phoneNumber, String status);

    List<OrderRecord> findByStatusOrderByCreatedAtAsc(String status);

    List<OrderRecord> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    Optional<OrderRecord> findBySourceCartId(Long sourceCartId);

    Optional<OrderRecord> findByClientRequestId(UUID clientRequestId);
}
