package com.sushimei.sushimei.backend.businessday;

import com.sushimei.sushimei.backend.entity.BusinessDay;
import com.sushimei.sushimei.backend.entity.BusinessDayClosure;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record BusinessDayResponse(
        Long businessDayId,
        LocalDate businessDate,
        BusinessDayStatus status,
        BigDecimal openingCashAmount,
        Instant openedAt,
        Long openedByUserId,
        Instant closedAt,
        Long closedByUserId,
        BigDecimal completedSalesAmount,
        BigDecimal cashSalesAmount,
        BigDecimal cashExpenseAmount,
        Long cashExpenseCount,
        BigDecimal transferSalesAmount,
        BigDecimal cardSalesAmount,
        BigDecimal unclassifiedSalesAmount,
        Long completedOrderCount,
        Long voidedOrderCount,
        BigDecimal expectedClosingCashAmount,
        BigDecimal actualClosingCashAmount,
        BigDecimal cashDifferenceAmount,
        Long closureId,
        Integer closureNumber) {

    public static BusinessDayResponse from(BusinessDay businessDay) {
        return new BusinessDayResponse(
                businessDay.getId(),
                businessDay.getBusinessDate(),
                businessDay.getStatus(),
                businessDay.getOpeningCashAmount(),
                businessDay.getOpenedAt(),
                businessDay.getOpenedByUserId(),
                businessDay.getClosedAt(),
                businessDay.getClosedByUserId(),
                businessDay.getCompletedSalesAmount(),
                businessDay.getCashSalesAmount(),
                businessDay.getCashExpenseAmount(),
                businessDay.getCashExpenseCount(),
                businessDay.getTransferSalesAmount(),
                businessDay.getCardSalesAmount(),
                businessDay.getUnclassifiedSalesAmount(),
                businessDay.getCompletedOrderCount(),
                businessDay.getVoidedOrderCount(),
                businessDay.getExpectedClosingCashAmount(),
                businessDay.getActualClosingCashAmount(),
                businessDay.getCashDifferenceAmount(),
                null,
                null);
    }

    /**
     * Returns a CLOSED business-day response from the exact immutable closure
     * record that captured its financial evidence.
     */
    public static BusinessDayResponse fromClosed(BusinessDay businessDay, BusinessDayClosure closure) {
        Objects.requireNonNull(businessDay, "businessDay must not be null");
        Objects.requireNonNull(closure, "closure must not be null");
        if (businessDay.getStatus() != BusinessDayStatus.CLOSED
                || !Objects.equals(businessDay.getId(), closure.getBusinessDayId())
                || closure.getId() == null) {
            throw new IllegalArgumentException("Closure does not match a closed business day");
        }
        return new BusinessDayResponse(
                businessDay.getId(),
                businessDay.getBusinessDate(),
                businessDay.getStatus(),
                closure.getOpeningCashAmount(),
                businessDay.getOpenedAt(),
                businessDay.getOpenedByUserId(),
                closure.getClosedAt(),
                closure.getClosedByUserId(),
                closure.getCompletedSalesAmount(),
                closure.getCashSalesAmount(),
                closure.getCashExpenseAmount(),
                closure.getCashExpenseCount(),
                closure.getTransferSalesAmount(),
                closure.getCardSalesAmount(),
                closure.getUnclassifiedSalesAmount(),
                closure.getCompletedOrderCount(),
                closure.getVoidedOrderCount(),
                closure.getExpectedClosingCashAmount(),
                closure.getActualClosingCashAmount(),
                closure.getCashDifferenceAmount(),
                closure.getId(),
                closure.getCloseNumber());
    }
}
