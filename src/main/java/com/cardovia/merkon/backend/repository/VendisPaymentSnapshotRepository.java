package com.cardovia.merkon.backend.repository;

import com.cardovia.merkon.backend.entity.VendisPaymentSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VendisPaymentSnapshotRepository extends JpaRepository<VendisPaymentSnapshot, Long> {

    List<VendisPaymentSnapshot> findByOrderIdOrderByPositionAsc(Long orderId);
}
