package com.cardovia.merkon.backend.security;

public record EmailVerificationResponse(String message) {

    static EmailVerificationResponse verified() {
        return new EmailVerificationResponse("Correo verificado correctamente.");
    }

    static EmailVerificationResponse resendAccepted() {
        return new EmailVerificationResponse("Si la cuenta requiere verificación, enviaremos un nuevo correo.");
    }
}
