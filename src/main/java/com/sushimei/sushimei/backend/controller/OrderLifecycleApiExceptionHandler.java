package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.order.OrderLifecycleError;
import com.sushimei.sushimei.backend.order.OrderLifecycleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderLifecycleApiExceptionHandler {

    @ExceptionHandler(OrderLifecycleException.class)
    public ResponseEntity<OrderLifecycleApiError> handle(OrderLifecycleException exception) {
        OrderLifecycleError error = exception.getError();
        return ResponseEntity.status(status(error)).body(new OrderLifecycleApiError(error.name(), message(error)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OrderLifecycleApiError> invalidVoidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(new OrderLifecycleApiError(
                OrderLifecycleError.ORDER_INVALID_VOID_REQUEST.name(),
                "La razón de cancelación no es válida."));
    }

    private HttpStatus status(OrderLifecycleError error) {
        return switch (error) {
            case ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ORDER_INVALID_VOID_REQUEST, ORDER_INVALID_PAYMENT_COLLECTION_REQUEST -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
    }

    private String message(OrderLifecycleError error) {
        return switch (error) {
            case ORDER_NOT_FOUND -> "La orden no existe.";
            case ORDER_INVALID_TRANSITION -> "La operación no es válida para el estado actual de la orden.";
            case ORDER_PAYMENT_NOT_VALIDATABLE -> "El pago de esta orden no puede validarse.";
            case ORDER_PAYMENT_COLLECTION_REQUIRED -> "Esta orden requiere cobro al entregar antes de completarse.";
            case ORDER_PAYMENT_COLLECTION_NOT_SUPPORTED -> "La orden no admite cobro al entregar.";
            case ORDER_INVALID_PAYMENT_COLLECTION_REQUEST -> "Los datos de cobro no son válidos.";
            case ORDER_PAYMENT_COLLECTION_BUSINESS_DAY_NOT_OPEN -> "El día de negocio correspondiente no está abierto.";
            case ORDER_OPERATION_NOT_SUPPORTED -> "La orden no admite esta operación.";
            case ORDER_INVALID_VOID_REQUEST -> "La razón de cancelación no es válida.";
        };
    }
}
