package com.cardovia.merkon.backend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-business persistence lock for current-day open/close and the physical-sale
 * boundary. It is never exposed as API data.
 */
@Entity
@Table(name = "business_day_operation_locks")
public class BusinessDayOperationLock {

    @Id
    @jakarta.persistence.Column(name = "business_id")
    private Long businessId;

    protected BusinessDayOperationLock() {
    }

    public static BusinessDayOperationLock forBusiness(Long businessId) {
        BusinessDayOperationLock lock = new BusinessDayOperationLock();
        lock.businessId = java.util.Objects.requireNonNull(businessId, "businessId must not be null");
        return lock;
    }
}
