package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.VendisPaymentSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VendisPaymentSnapshotRepository extends JpaRepository<VendisPaymentSnapshot, Long> {

    List<VendisPaymentSnapshot> findByOrderIdOrderByPositionAsc(Long orderId);
}
