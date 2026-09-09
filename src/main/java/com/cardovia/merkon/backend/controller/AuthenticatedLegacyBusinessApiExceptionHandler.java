package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.security.SecurityApiError;
import com.cardovia.merkon.backend.security.SecurityApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps trusted-business authorization failures for the retained legacy routes only. */
@RestControllerAdvice(assignableTypes = {OrderController.class, ChatController.class})
public class AuthenticatedLegacyBusinessApiExceptionHandler {

    @ExceptionHandler(SecurityApiException.class)
    ResponseEntity<SecurityApiError> handle(SecurityApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new SecurityApiError(exception.code(), exception.getMessage()));
    }
}
