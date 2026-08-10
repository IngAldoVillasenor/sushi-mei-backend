package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationException;
import com.sushimei.sushimei.backend.catalog.CatalogDomainError;
import com.sushimei.sushimei.backend.catalog.MenuCatalogItemNotFoundException;
import com.sushimei.sushimei.backend.catalog.MenuCatalogValidationException;
import com.sushimei.sushimei.backend.catalog.MenuCatalogVersionConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {MenuCatalogController.class, MenuConfigurationController.class})
public class MenuCatalogApiExceptionHandler {

    @ExceptionHandler(MenuCatalogItemNotFoundException.class)
    public ResponseEntity<MenuCatalogApiError> handleNotFound(MenuCatalogItemNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Elemento de menú no encontrado.");
    }

    @ExceptionHandler(CatalogConfigurationException.class)
    public ResponseEntity<MenuCatalogApiError> handleConfiguration(CatalogConfigurationException exception) {
        CatalogDomainError error = exception.getError();
        return switch (error) {
            case CATALOG_TAG_NOT_FOUND -> error(HttpStatus.NOT_FOUND, error.name(), "Etiqueta de catálogo no encontrada.");
            case MENU_SELECTION_GROUP_NOT_FOUND -> error(HttpStatus.NOT_FOUND, error.name(),
                    "Grupo de selección no encontrado.");
            case MENU_SELECTION_RULE_NOT_FOUND -> error(HttpStatus.NOT_FOUND, error.name(),
                    "Regla de selección no encontrada.");
            case CATALOG_TAG_VERSION_CONFLICT, MENU_SELECTION_GROUP_VERSION_CONFLICT,
                    MENU_SELECTION_RULE_VERSION_CONFLICT, MENU_ITEM_NOT_ORDERABLE, MENU_ITEM_UNAVAILABLE ->
                    error(HttpStatus.CONFLICT, error.name(), "La configuración del catálogo cambió o no está disponible.");
            case MENU_CONFIGURATION_INCOMPLETE, MENU_SELECTION_NOT_ALLOWED,
                    MENU_SELECTION_DUPLICATE_NOT_ALLOWED, MENU_CONFIGURATION_CYCLE, MENU_CONFIGURATION_INVALID ->
                    error(HttpStatus.BAD_REQUEST, error.name(), "La configuración solicitada no es válida.");
        };
    }

    @ExceptionHandler({
            MenuCatalogValidationException.class,
            MethodArgumentNotValidException.class,
            DataIntegrityViolationException.class
    })
    public ResponseEntity<MenuCatalogApiError> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MENU_ITEM", "Solicitud de catálogo inválida.");
    }

    @ExceptionHandler({
            MenuCatalogVersionConflictException.class,
            ObjectOptimisticLockingFailureException.class
    })
    public ResponseEntity<MenuCatalogApiError> handleVersionConflict(Exception exception) {
        return error(HttpStatus.CONFLICT, "MENU_ITEM_VERSION_CONFLICT",
                "El elemento de menú fue modificado por otra operación.");
    }

    private ResponseEntity<MenuCatalogApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new MenuCatalogApiError(code, message));
    }
}
