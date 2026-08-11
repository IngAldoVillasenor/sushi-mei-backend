package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.order.OrderLifecycleError;
import com.sushimei.sushimei.backend.order.OrderLifecycleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderLifecycleApiExceptionHandler {

    @ExceptionHandler(OrderLifecycleException.class)
    public ResponseEntity<OrderLifecycleApiError> handle(OrderLifecycleException exception) {
        OrderLifecycleError error = exception.getError();
        return ResponseEntity.status(status(error)).body(new OrderLifecycleApiError(error.name(), message(error)));
    }

    private HttpStatus status(OrderLifecycleError error) {
        return error == OrderLifecycleError.ORDER_NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
    }

    private String message(OrderLifecycleError error) {
        return switch (error) {
            case ORDER_NOT_FOUND -> "La orden no existe.";
            case ORDER_INVALID_TRANSITION -> "La operación no es válida para el estado actual de la orden.";
            case ORDER_PAYMENT_NOT_VALIDATABLE -> "El pago de esta orden no puede validarse.";
            case ORDER_OPERATION_NOT_SUPPORTED -> "La orden no admite esta operación.";
        };
    }
}
