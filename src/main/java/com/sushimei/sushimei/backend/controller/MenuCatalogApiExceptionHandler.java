package com.sushimei.sushimei.backend.controller;

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

@RestControllerAdvice(assignableTypes = MenuCatalogController.class)
public class MenuCatalogApiExceptionHandler {

    @ExceptionHandler(MenuCatalogItemNotFoundException.class)
    public ResponseEntity<MenuCatalogApiError> handleNotFound(MenuCatalogItemNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Elemento de menú no encontrado.");
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
