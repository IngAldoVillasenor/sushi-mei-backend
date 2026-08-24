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
public interface OrderRepository extends JpaRepository<OrderRecord, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<OrderRecord> {
    @Query("SELECT COUNT(o) FROM OrderRecord o " +
           "WHERE o.status = 'VOIDED' " +
           "AND o.createdAt >= :from " +
           "AND o.createdAt < :to")
    long countVoidedOrders(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from, @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    @Query("SELECT COUNT(o) FROM OrderRecord o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.createdAt >= :from " +
           "AND o.createdAt < :to")
    long countCompletedOrders(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from, @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    @Query("SELECT SUM(o.totalAmountAmount) FROM OrderRecord o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.createdAt >= :from " +
           "AND o.createdAt < :to")
    java.math.BigDecimal sumCompletedRevenue(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from, @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    @Query("SELECT new com.sushimei.sushimei.backend.orderread.SalesBySourceResponse(o.orderSource, COUNT(o), SUM(o.totalAmountAmount)) " +
           "FROM OrderRecord o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.createdAt >= :from " +
           "AND o.createdAt < :to " +
           "GROUP BY o.orderSource " +
           "ORDER BY SUM(o.totalAmountAmount) DESC, o.orderSource ASC")
    java.util.List<com.sushimei.sushimei.backend.orderread.SalesBySourceResponse> aggregateCompletedSalesBySource(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from, @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    @Query("SELECT o FROM OrderRecord o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.createdAt >= :from " +
           "AND o.createdAt < :to")
    List<OrderRecord> findCompletedForBusinessDate(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    @Query("SELECT COUNT(o) FROM OrderRecord o "
            + "WHERE o.createdAt >= :from "
            + "AND o.createdAt < :to "
            + "AND (o.status IS NULL OR o.status NOT IN :terminalStatuses)")
    long countNonTerminalForBusinessDate(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("terminalStatuses") List<String> terminalStatuses);

    OrderRecord findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(String phoneNumber, String status);

    List<OrderRecord> findByStatusOrderByCreatedAtAsc(String status);

    List<OrderRecord> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    List<OrderRecord> findByStatusInOrderByCreatedAtAscIdAsc(List<String> statuses);

    /**
     * Lightweight summary support: returns only identifiers for orders that have persisted structured lines.
     * It deliberately does not initialize the line graph.
     */
    @Query("select distinct orderRecord.id from OrderRecord orderRecord join orderRecord.orderLines orderLine "
            + "where orderRecord.id in :orderIds")
    List<Long> findIdsWithOrderLines(@Param("orderIds") List<Long> orderIds);

    /**
     * Loads a single order and its line/source-line evidence. Selection snapshots are loaded separately
     * in one bulk query to avoid Hibernate's multiple-bag fetch limitation.
     */
    @Query("select distinct orderRecord from OrderRecord orderRecord "
            + "left join fetch orderRecord.orderLines orderLine "
            + "left join fetch orderLine.sourcePaidLine "
            + "where orderRecord.id = :id")
    Optional<OrderRecord> findOperationalDetailById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orderRecord from OrderRecord orderRecord where orderRecord.id = :id")
    Optional<OrderRecord> findByIdForUpdate(@Param("id") Long id);

    Optional<OrderRecord> findBySourceCartId(Long sourceCartId);

    Optional<OrderRecord> findByClientRequestId(UUID clientRequestId);

    @Query("select distinct orderRecord from OrderRecord orderRecord left join fetch orderRecord.orderLines "
            + "where orderRecord.clientRequestId = :clientRequestId")
    Optional<OrderRecord> findByClientRequestIdWithOrderLines(@Param("clientRequestId") UUID clientRequestId);

    Optional<OrderRecord> findByOrderSourceAndExternalOrderId(
            com.sushimei.sushimei.backend.entity.OrderSource orderSource,
            String externalOrderId);



}
