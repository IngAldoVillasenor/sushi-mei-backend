package com.sushimei.sushimei.backend.security;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Keeps duplicate-user race handling local to OWNER user management rather
 * than changing data-integrity responses for authentication or legacy APIs.
 */
@RestControllerAdvice(assignableTypes = SecurityUserController.class)
public class SecurityUserDataIntegrityExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<SecurityApiError> duplicateUserConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new SecurityApiError("INVALID_USER", "El usuario no es válido."));
    }
}