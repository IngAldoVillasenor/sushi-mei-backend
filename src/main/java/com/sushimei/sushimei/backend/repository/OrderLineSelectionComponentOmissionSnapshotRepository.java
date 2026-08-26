package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.OrderLineSelectionComponentOmissionSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Bulk read repository for occurrence-level immutable component-omission evidence. */
public interface OrderLineSelectionComponentOmissionSnapshotRepository
        extends JpaRepository<OrderLineSelectionComponentOmissionSnapshot, Long> {

    @Query("select omission from OrderLineSelectionComponentOmissionSnapshot omission "
            + "join fetch omission.selectionSnapshot snapshot "
            + "join snapshot.orderLine line "
            + "where line.order.id = :orderId "
            + "order by snapshot.id asc, omission.componentDisplayOrder asc, omission.id asc")
    List<OrderLineSelectionComponentOmissionSnapshot> findByOrderIdForOperationalRead(@Param("orderId") Long orderId);
}
