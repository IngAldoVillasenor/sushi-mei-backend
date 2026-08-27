package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.OrderLineComponentOmissionSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineComponentOmissionSnapshotRepository
        extends JpaRepository<OrderLineComponentOmissionSnapshot, Long> {

    @Query("select omission from OrderLineComponentOmissionSnapshot omission "
            + "where omission.orderLine.order.id = :orderId "
            + "order by omission.componentDisplayOrder asc, omission.id asc")
    List<OrderLineComponentOmissionSnapshot> findByOrderIdForOperationalRead(@Param("orderId") Long orderId);
}
