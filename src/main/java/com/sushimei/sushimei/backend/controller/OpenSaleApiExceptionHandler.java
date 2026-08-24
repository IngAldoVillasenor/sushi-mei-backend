package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.pos.OpenSaleError;
import com.sushimei.sushimei.backend.pos.OpenSaleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OpenSaleController.class)
public class OpenSaleApiExceptionHandler {

    @ExceptionHandler(OpenSaleException.class)
    public ResponseEntity<OpenSaleApiError> openSale(OpenSaleException exception) {
        return switch (exception.getError()) {
            case OPEN_SALE_IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, exception.getError(),
                    "La solicitud ya pertenece a otra venta libre.");
            case OPEN_SALE_BUSINESS_DAY_OPEN_REQUIRED -> error(HttpStatus.CONFLICT, exception.getError(),
                    "Se requiere un día de negocio abierto para registrar una venta libre.");
            case OPEN_SALE_INVALID -> error(HttpStatus.BAD_REQUEST, exception.getError(),
                    "La venta libre no es válida.");
        };
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<OpenSaleApiError> invalid(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, OpenSaleError.OPEN_SALE_INVALID, "La venta libre no es válida.");
    }

    private ResponseEntity<OpenSaleApiError> error(HttpStatus status, OpenSaleError error, String message) {
        return ResponseEntity.status(status).body(new OpenSaleApiError(error.name(), message));
    }
}
