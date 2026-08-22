package com.sushimei.sushimei.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Singleton persistence lock for the restaurant's current-day open/close and
 * physical-sale boundary. It is seeded by Flyway and is never exposed as API data.
 */
@Entity
@Table(name = "business_day_operation_locks")
public class BusinessDayOperationLock {

    @Id
    @Column(name = "lock_key")
    private Integer lockKey;

    protected BusinessDayOperationLock() {
    }
}
