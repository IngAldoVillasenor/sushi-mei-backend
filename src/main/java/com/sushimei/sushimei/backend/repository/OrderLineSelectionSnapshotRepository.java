package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Bulk read repository for immutable selection evidence belonging to one persisted order. */
@Repository
public interface OrderLineSelectionSnapshotRepository extends JpaRepository<OrderLineSelectionSnapshot, Long> {

    @Query("select snapshot from OrderLineSelectionSnapshot snapshot "
            + "join fetch snapshot.orderLine orderLine "
            + "left join fetch snapshot.parentSelection "
            + "where orderLine.order.id = :orderId "
            + "order by orderLine.linePosition asc, snapshot.id asc")
    List<OrderLineSelectionSnapshot> findByOrderIdForOperationalRead(@Param("orderId") Long orderId);
}
