package com.sushimei.sushimei.backend.order;

import java.time.LocalDateTime;

/**
 * Minimal, non-entity order projection used to select the authoritative Business Day before
 * payment collection acquires the mutable order row lock.
 */
public record OrderPaymentCollectionReference(Long id, LocalDateTime createdAt) {
}
