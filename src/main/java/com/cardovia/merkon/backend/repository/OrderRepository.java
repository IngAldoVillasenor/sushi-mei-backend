package com.cardovia.merkon.backend.repository;

import com.cardovia.merkon.backend.entity.OrderRecord;
import com.cardovia.merkon.backend.order.OrderPaymentCollectionReference;
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

    Optional<OrderRecord> findByIdAndBusinessId(Long id, Long businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orderRecord from OrderRecord orderRecord where orderRecord.id = :id and orderRecord.business.id = :businessId")
    Optional<OrderRecord> findByIdAndBusinessIdForUpdate(@Param("id") Long id, @Param("businessId") Long businessId);

    List<OrderRecord> findByBusinessIdAndStatusInOrderByCreatedAtAscIdAsc(Long businessId, List<String> statuses);

    Optional<OrderRecord> findByBusinessIdAndClientRequestId(Long businessId, UUID clientRequestId);

    @Query("select distinct orderRecord from OrderRecord orderRecord left join fetch orderRecord.orderLines "
            + "where orderRecord.business.id = :businessId and orderRecord.clientRequestId = :clientRequestId")
    Optional<OrderRecord> findByBusinessIdAndClientRequestIdWithOrderLines(
            @Param("businessId") Long businessId, @Param("clientRequestId") UUID clientRequestId);

    Optional<OrderRecord> findByBusinessIdAndOrderSourceAndExternalOrderId(
            Long businessId, com.cardovia.merkon.backend.entity.OrderSource orderSource, String externalOrderId);

    @Query("SELECT COUNT(o) FROM OrderRecord o WHERE o.business.id = :businessId AND o.status = 'VOIDED' AND o.createdAt >= :from AND o.createdAt < :to")
    long countVoidedOrdersForBusiness(@Param("businessId") Long businessId, @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    @Query("SELECT COUNT(o) FROM OrderRecord o WHERE o.business.id = :businessId AND o.status = 'COMPLETED' AND o.createdAt >= :from AND o.createdAt < :to")
    long countCompletedOrdersForBusiness(@Param("businessId") Long businessId, @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    @Query("SELECT SUM(o.totalAmountAmount) FROM OrderRecord o WHERE o.business.id = :businessId AND o.status = 'COMPLETED' AND o.createdAt >= :from AND o.createdAt < :to")
    java.math.BigDecimal sumCompletedRevenueForBusiness(@Param("businessId") Long businessId, @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    @Query("SELECT new com.cardovia.merkon.backend.orderread.SalesBySourceResponse(o.orderSource, COUNT(o), SUM(o.totalAmountAmount)) "
            + "FROM OrderRecord o WHERE o.business.id = :businessId AND o.status = 'COMPLETED' AND o.createdAt >= :from AND o.createdAt < :to "
            + "GROUP BY o.orderSource ORDER BY SUM(o.totalAmountAmount) DESC, o.orderSource ASC")
    java.util.List<com.cardovia.merkon.backend.orderread.SalesBySourceResponse> aggregateCompletedSalesBySourceForBusiness(
            @Param("businessId") Long businessId, @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    @Query("SELECT o FROM OrderRecord o WHERE o.business.id = :businessId AND o.status = 'COMPLETED' AND o.createdAt >= :from AND o.createdAt < :to")
    List<OrderRecord> findCompletedForBusinessDateForBusiness(@Param("businessId") Long businessId,
                                                               @Param("from") java.time.LocalDateTime from,
                                                               @Param("to") java.time.LocalDateTime to);

    @Query("SELECT COUNT(o) FROM OrderRecord o WHERE o.business.id = :businessId AND o.createdAt >= :from AND o.createdAt < :to AND (o.status IS NULL OR o.status NOT IN :terminalStatuses)")
    long countNonTerminalForBusinessDateForBusiness(@Param("businessId") Long businessId,
                                                     @Param("from") java.time.LocalDateTime from,
                                                     @Param("to") java.time.LocalDateTime to,
                                                     @Param("terminalStatuses") List<String> terminalStatuses);
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

    @Query("SELECT new com.cardovia.merkon.backend.orderread.SalesBySourceResponse(o.orderSource, COUNT(o), SUM(o.totalAmountAmount)) " +
           "FROM OrderRecord o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.createdAt >= :from " +
           "AND o.createdAt < :to " +
           "GROUP BY o.orderSource " +
           "ORDER BY SUM(o.totalAmountAmount) DESC, o.orderSource ASC")
    java.util.List<com.cardovia.merkon.backend.orderread.SalesBySourceResponse> aggregateCompletedSalesBySource(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from, @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

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

    OrderRecord findFirstByBusinessIdAndPhoneNumberAndStatusOrderByCreatedAtDesc(
            Long businessId,
            String phoneNumber,
            String status);

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

    @Query("select distinct orderRecord from OrderRecord orderRecord "
            + "left join fetch orderRecord.orderLines orderLine "
            + "left join fetch orderLine.sourcePaidLine "
            + "where orderRecord.id = :id and orderRecord.business.id = :businessId")
    Optional<OrderRecord> findOperationalDetailByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orderRecord from OrderRecord orderRecord where orderRecord.id = :id")
    Optional<OrderRecord> findByIdForUpdate(@Param("id") Long id);

    /**
     * Deliberately projects scalar order evidence without loading an OrderRecord into the
     * persistence context. Payment collection uses it only to identify the business date before
     * it takes the Business Day operation lock and then locks the mutable order row.
     */
    @Query("select new com.cardovia.merkon.backend.order.OrderPaymentCollectionReference("
            + "orderRecord.id, orderRecord.createdAt) "
            + "from OrderRecord orderRecord where orderRecord.id = :id")
    Optional<OrderPaymentCollectionReference> findPaymentCollectionReferenceById(@Param("id") Long id);

    @Query("select new com.cardovia.merkon.backend.order.OrderPaymentCollectionReference("
            + "orderRecord.id, orderRecord.createdAt) from OrderRecord orderRecord "
            + "where orderRecord.id = :id and orderRecord.business.id = :businessId")
    Optional<OrderPaymentCollectionReference> findPaymentCollectionReferenceByIdAndBusinessId(
            @Param("id") Long id, @Param("businessId") Long businessId);

    Optional<OrderRecord> findByBusinessIdAndSourceCartId(Long businessId, Long sourceCartId);

    /** Legacy test/history helper; tenant-visible services must use the business-scoped form. */
    Optional<OrderRecord> findBySourceCartId(Long sourceCartId);

    Optional<OrderRecord> findByClientRequestId(UUID clientRequestId);

    @Query("select distinct orderRecord from OrderRecord orderRecord left join fetch orderRecord.orderLines "
            + "where orderRecord.clientRequestId = :clientRequestId")
    Optional<OrderRecord> findByClientRequestIdWithOrderLines(@Param("clientRequestId") UUID clientRequestId);

    Optional<OrderRecord> findByOrderSourceAndExternalOrderId(
            com.cardovia.merkon.backend.entity.OrderSource orderSource,
            String externalOrderId);



}
