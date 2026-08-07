package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.entity.OrderSource;

/** Trusted application command; checkout facts are read from persisted state. */
public record CheckoutCompletionCommand(String phoneNumber, Long sourceCartId, OrderSource orderSource) {
}
