package com.cardovia.merkon.backend.security;

public record PasswordRecoveryResponse(String message) {

    static PasswordRecoveryResponse requestAccepted() {
        return new PasswordRecoveryResponse("Si la cuenta existe y es elegible, enviaremos un correo para restablecer la contraseña.");
    }
}
