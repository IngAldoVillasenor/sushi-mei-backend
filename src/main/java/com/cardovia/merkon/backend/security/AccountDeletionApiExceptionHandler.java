package com.cardovia.merkon.backend.security;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=AccountDeletionController.class)
class AccountDeletionApiExceptionHandler {
 @ExceptionHandler(SecurityApiException.class) ResponseEntity<SecurityApiError> security(SecurityApiException e){return ResponseEntity.status(e.status()).body(new SecurityApiError(e.code(),e.getMessage()));}
 @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<SecurityApiError> invalid(){return ResponseEntity.badRequest().body(new SecurityApiError("ACCOUNT_DELETION_INVALID_REQUEST","Solicitud de eliminación inválida."));}
}
