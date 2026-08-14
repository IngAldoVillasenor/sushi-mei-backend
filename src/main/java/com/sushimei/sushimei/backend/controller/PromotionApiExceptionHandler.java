package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationException;
import com.sushimei.sushimei.backend.catalog.CatalogDomainError;
import com.sushimei.sushimei.backend.catalog.MenuCatalogItemNotFoundException;
import com.sushimei.sushimei.backend.catalog.MenuCatalogValidationException;
import com.sushimei.sushimei.backend.promotion.PromotionError;
import com.sushimei.sushimei.backend.promotion.PromotionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PromotionController.class)
public class PromotionApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionApiExceptionHandler.class);

    @ExceptionHandler(PromotionException.class)
    public ResponseEntity<PromotionApiError> handlePromotion(PromotionException exception) {
        PromotionError error = exception.getError();
        return switch (error) {
            case PROMOTION_NOT_FOUND -> error(HttpStatus.NOT_FOUND, error.name(), "Promocion no encontrada.");
            case PROMOTION_VERSION_CONFLICT, PROMOTION_CONFIGURATION_CONFLICT ->
                    error(HttpStatus.CONFLICT, error.name(), "La promocion cambio o tiene una configuracion conflictiva.");
            case PROMOTION_SCHEDULE_CONFLICT ->
                    error(HttpStatus.CONFLICT, error.name(),
                            "Otra promocion activa con la misma prioridad coincide en dias y productos.");
            case PROMOTION_REWARD_INVALID ->
                    error(HttpStatus.BAD_REQUEST, error.name(), "La promocion seleccionada ya no esta disponible para esta orden.");
            case INVALID_PROMOTION, PROMOTION_QUOTE_INVALID ->
                    error(HttpStatus.BAD_REQUEST, error.name(), "La solicitud de promocion no es valida.");
        };
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<PromotionApiError> handleOptimisticLockFailure(ObjectOptimisticLockingFailureException exception) {
        return error(HttpStatus.CONFLICT, PromotionError.PROMOTION_VERSION_CONFLICT.name(),
                "La promocion fue modificada por otra operacion.");
    }

    @ExceptionHandler(MenuCatalogItemNotFoundException.class)
    public ResponseEntity<PromotionApiError> handleCatalogItemNotFound(MenuCatalogItemNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Elemento de menu no encontrado.");
    }

    @ExceptionHandler(CatalogConfigurationException.class)
    public ResponseEntity<PromotionApiError> handleCatalogConfiguration(CatalogConfigurationException exception) {
        CatalogDomainError error = exception.getError();
        return switch (error) {
            case CATALOG_TAG_NOT_FOUND, MENU_SELECTION_GROUP_NOT_FOUND, MENU_SELECTION_RULE_NOT_FOUND ->
                    error(HttpStatus.NOT_FOUND, error.name(), "Recurso de configuracion de catalogo no encontrado.");
            case CATALOG_TAG_VERSION_CONFLICT, MENU_SELECTION_GROUP_VERSION_CONFLICT,
                    MENU_SELECTION_RULE_VERSION_CONFLICT, MENU_ITEM_NOT_ORDERABLE, MENU_ITEM_UNAVAILABLE ->
                    error(HttpStatus.CONFLICT, error.name(), "La configuracion de catalogo cambio o no esta disponible.");
            case MENU_CONFIGURATION_INCOMPLETE, MENU_SELECTION_NOT_ALLOWED,
                    MENU_SELECTION_DUPLICATE_NOT_ALLOWED, MENU_CONFIGURATION_CYCLE, MENU_CONFIGURATION_INVALID ->
                    error(HttpStatus.BAD_REQUEST, error.name(), "La configuracion solicitada no es valida.");
        };
    }

    @ExceptionHandler({MenuCatalogValidationException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, DataIntegrityViolationException.class})
    public ResponseEntity<PromotionApiError> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION", "La solicitud de promocion no es valida.");
    }

    private ResponseEntity<PromotionApiError> error(HttpStatus status, String code, String message) {
        LOGGER.warn("promotion_api_error requestId={} status={} code={}",
                MDC.get("requestId"), status.value(), code);
        return ResponseEntity.status(status).body(new PromotionApiError(code, message));
    }
}
