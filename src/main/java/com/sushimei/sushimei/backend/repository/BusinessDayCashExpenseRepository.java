package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.BusinessDayCashExpense;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusinessDayCashExpenseRepository extends JpaRepository<BusinessDayCashExpense, Long> {

    Optional<BusinessDayCashExpense> findByClientRequestId(UUID clientRequestId);

    List<BusinessDayCashExpense> findByBusinessDayIdOrderByCreatedAtAscIdAsc(Long businessDayId);

    long countByBusinessDayId(Long businessDayId);

    @Query("select coalesce(sum(expense.amount), 0) from BusinessDayCashExpense expense "
            + "where expense.businessDayId = :businessDayId")
    BigDecimal sumAmountByBusinessDayId(@Param("businessDayId") Long businessDayId);
}
