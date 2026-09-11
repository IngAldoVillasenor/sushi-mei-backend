package com.cardovia.merkon.backend.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PasswordRecoveryController.class)
class PasswordRecoveryApiExceptionHandler {

    @ExceptionHandler(SecurityApiException.class)
    ResponseEntity<SecurityApiError> security(SecurityApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new SecurityApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<SecurityApiError> invalidRequest() {
        return ResponseEntity.badRequest().body(new SecurityApiError(
                "PASSWORD_RECOVERY_INVALID_REQUEST", "Solicitud de restablecimiento inv\u00e1lida."));
    }
}
