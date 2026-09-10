package com.cardovia.merkon.backend.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps anonymous registration failures separate from authenticated user administration errors. */
@RestControllerAdvice(assignableTypes = PublicRegistrationController.class)
class PublicRegistrationApiExceptionHandler {

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
        return ResponseEntity.badRequest()
                .body(new SecurityApiError("REGISTRATION_INVALID_REQUEST", "Solicitud de registro invalida."));
    }
}
