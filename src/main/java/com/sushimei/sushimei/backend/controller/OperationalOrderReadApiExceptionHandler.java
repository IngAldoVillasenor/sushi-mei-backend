package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.orderread.OperationalOrderReadException;
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
}
