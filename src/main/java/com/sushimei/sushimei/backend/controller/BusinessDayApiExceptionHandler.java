package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.businessday.BusinessDayError;
import com.sushimei.sushimei.backend.businessday.BusinessDayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BusinessDayController.class)
public class BusinessDayApiExceptionHandler {

    @ExceptionHandler(BusinessDayException.class)
    public ResponseEntity<BusinessDayApiError> businessDay(BusinessDayException exception) {
        BusinessDayError error = exception.getError();
        return ResponseEntity.status(status(error)).body(new BusinessDayApiError(error.name(), message(error)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<BusinessDayApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new BusinessDayApiError(
                BusinessDayError.BUSINESS_DAY_INVALID.name(), "La información del día de negocio no es válida."));
    }

    private static HttpStatus status(BusinessDayError error) {
        return error == BusinessDayError.BUSINESS_DAY_INVALID ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
    }

    private static String message(BusinessDayError error) {
        return switch (error) {
            case BUSINESS_DAY_INVALID -> "La información del día de negocio no es válida.";
            case BUSINESS_DAY_ALREADY_OPEN -> "Ya existe una caja abierta.";
            case BUSINESS_DAY_ALREADY_CLOSED -> "El día de negocio actual ya fue cerrado.";
            case BUSINESS_DAY_CLOSED -> "El día de negocio ya fue cerrado.";
            case BUSINESS_DAY_HAS_ACTIVE_ORDERS -> "No se puede cerrar la caja mientras existan órdenes activas.";
            case BUSINESS_DAY_NOT_OPEN -> "No existe una caja abierta para cerrar.";
            case BUSINESS_DAY_OPEN_REQUIRED -> "Se requiere una caja abierta para registrar esta venta.";
            case BUSINESS_DAY_NOT_CLOSED -> "El día de negocio no está cerrado.";
            case BUSINESS_DAY_REOPEN_NOT_ALLOWED -> "El día de negocio no se puede reabrir.";
            case BUSINESS_DAY_CASH_EXPENSE_IDEMPOTENCY_CONFLICT ->
                    "La solicitud de gasto no coincide con el movimiento ya registrado.";
            case BUSINESS_DAY_CASH_EXPENSES_EXCEED_AVAILABLE_CASH ->
                    "Los gastos de efectivo exceden el efectivo disponible en caja.";
        };
    }
}
