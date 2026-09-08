package com.cardovia.merkon.backend.checkout;

import com.cardovia.merkon.backend.entity.OrderSource;

/** Trusted application command; checkout facts are read from persisted state. */
public record CheckoutCompletionCommand(String phoneNumber, Long sourceCartId, OrderSource orderSource) {
}
