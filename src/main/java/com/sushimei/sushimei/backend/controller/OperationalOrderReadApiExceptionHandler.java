package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.orderread.OperationalOrderReadException;
import com.sushimei.sushimei.backend.orderread.InvalidDateRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OperationalOrderController.class)
public class OperationalOrderReadApiExceptionHandler {

    @ExceptionHandler(OperationalOrderReadException.class)
    public ResponseEntity<OperationalOrderReadApiError> missing(OperationalOrderReadException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new OperationalOrderReadApiError("ORDER_NOT_FOUND", "La orden no existe."));
    }

    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<OperationalOrderReadApiError> invalidRange(InvalidDateRangeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new OperationalOrderReadApiError("INVALID_RANGE", exception.getMessage()));
    }
}
