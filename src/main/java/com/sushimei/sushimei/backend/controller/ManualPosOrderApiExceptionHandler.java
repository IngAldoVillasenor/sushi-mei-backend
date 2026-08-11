package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationException;
import com.sushimei.sushimei.backend.catalog.CatalogDomainError;
import com.sushimei.sushimei.backend.catalog.MenuCatalogItemNotFoundException;
import com.sushimei.sushimei.backend.pos.ManualPosOrderError;
import com.sushimei.sushimei.backend.pos.ManualPosOrderException;
import com.sushimei.sushimei.backend.promotion.PromotionError;
import com.sushimei.sushimei.backend.promotion.PromotionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ManualPosOrderController.class)
public class ManualPosOrderApiExceptionHandler {

    @ExceptionHandler(ManualPosOrderException.class)
    public ResponseEntity<ManualPosOrderApiError> manual(ManualPosOrderException exception) {
        return switch (exception.getError()) {
            case ORDER_IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, exception.getError(), "La solicitud ya pertenece a otra orden.");
            case ORDER_MENU_ITEM_NOT_FOUND -> error(HttpStatus.NOT_FOUND, exception.getError(), "Elemento de menu no encontrado.");
            case ORDER_MENU_ITEM_UNAVAILABLE -> error(HttpStatus.CONFLICT, exception.getError(), "El elemento de menu no esta disponible.");
            case ORDER_PROMOTION_CONFLICT -> error(HttpStatus.CONFLICT, exception.getError(), "La promocion aplicable tiene un conflicto.");
            case ORDER_FORBIDDEN_OPERATION -> error(HttpStatus.FORBIDDEN, exception.getError(), "La operacion no esta permitida.");
            case ORDER_INVALID, ORDER_CONFIGURATION_INVALID -> error(HttpStatus.BAD_REQUEST, exception.getError(), "La solicitud de orden no es valida.");
        };
    }

    @ExceptionHandler(MenuCatalogItemNotFoundException.class)
    public ResponseEntity<ManualPosOrderApiError> menuItemNotFound(MenuCatalogItemNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, ManualPosOrderError.ORDER_MENU_ITEM_NOT_FOUND, "Elemento de menu no encontrado.");
    }

    @ExceptionHandler(CatalogConfigurationException.class)
    public ResponseEntity<ManualPosOrderApiError> catalog(CatalogConfigurationException exception) {
        return switch (exception.getError()) {
            case MENU_ITEM_UNAVAILABLE, MENU_ITEM_NOT_ORDERABLE ->
                    error(HttpStatus.CONFLICT, ManualPosOrderError.ORDER_MENU_ITEM_UNAVAILABLE, "El elemento de menu no esta disponible.");
            default -> error(HttpStatus.BAD_REQUEST, ManualPosOrderError.ORDER_CONFIGURATION_INVALID,
                    "La configuracion solicitada no es valida.");
        };
    }

    @ExceptionHandler(PromotionException.class)
    public ResponseEntity<ManualPosOrderApiError> promotion(PromotionException exception) {
        if (exception.getError() == PromotionError.PROMOTION_CONFIGURATION_CONFLICT) {
            return error(HttpStatus.CONFLICT, ManualPosOrderError.ORDER_PROMOTION_CONFLICT,
                    "La promocion aplicable tiene un conflicto.");
        }
        return error(HttpStatus.BAD_REQUEST, ManualPosOrderError.ORDER_CONFIGURATION_INVALID,
                "La solicitud de orden no es valida.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ManualPosOrderApiError> invalid(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, ManualPosOrderError.ORDER_INVALID, "La solicitud de orden no es valida.");
    }

    private ResponseEntity<ManualPosOrderApiError> error(HttpStatus status, ManualPosOrderError code, String message) {
        return ResponseEntity.status(status).body(new ManualPosOrderApiError(code.name(), message));
    }
}
