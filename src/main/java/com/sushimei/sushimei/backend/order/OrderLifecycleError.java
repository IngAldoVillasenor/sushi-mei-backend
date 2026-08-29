package com.sushimei.sushimei.backend.order;

public enum OrderLifecycleError {
    ORDER_NOT_FOUND,
    ORDER_INVALID_TRANSITION,
    ORDER_PAYMENT_NOT_VALIDATABLE,
    ORDER_OPERATION_NOT_SUPPORTED,
    ORDER_INVALID_VOID_REQUEST
}
