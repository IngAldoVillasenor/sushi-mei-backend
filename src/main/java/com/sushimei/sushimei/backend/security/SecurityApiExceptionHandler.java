package com.sushimei.sushimei.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AuthController.class, SecurityUserController.class})
public class SecurityApiExceptionHandler {

    @ExceptionHandler(SecurityApiException.class)
    ResponseEntity<SecurityApiError> handle(SecurityApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new SecurityApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<SecurityApiError> optimisticConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new SecurityApiError("USER_VERSION_CONFLICT", "El usuario fue modificado por otra operación."));
    }

    @ExceptionHandler({
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<SecurityApiError> invalidRequest() {
        return ResponseEntity.badRequest()
                .body(new SecurityApiError("INVALID_USER", "Solicitud de seguridad inválida."));
    }
}