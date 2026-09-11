package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Small provider-neutral MerkON password-recovery email renderer. */
@Component
class PasswordRecoveryEmailTemplate {

    private final TransactionalEmailProperties properties;
    private final Clock clock;

    PasswordRecoveryEmailTemplate(TransactionalEmailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    TransactionalEmail render(String recipient, String plaintextToken, Instant expiresAt, Long tokenId) {
        String resetUrl = UriComponentsBuilder.fromUriString(properties.passwordResetBaseUrl())
                .replaceQueryParam("token", plaintextToken)
                .build()
                .toUriString();
        long minutes = Math.max(1, Duration.between(clock.instant(), expiresAt).toMinutes());
        String subject = "Restablece tu contrase\u00f1a de MerkON";
        String text = "Recibimos una solicitud para restablecer tu contrase\u00f1a de MerkON.\n\n"
                + "Abre la aplicaci\u00f3n o sitio de MerkON y usa este enlace para continuar:\n"
                + resetUrl + "\n\n"
                + "El enlace vence aproximadamente en " + minutes + " minuto(s). "
                + "Si no solicitaste este cambio, puedes ignorar este correo.\n\n"
                + "Soporte: " + properties.supportAddress();
        String html = """
                <!doctype html>
                <html lang="es"><body style="font-family:Arial,sans-serif;color:#1f2937;line-height:1.5">
                <h1 style="font-size:22px">Restablece tu contrase\u00f1a de MerkON</h1>
                <p>Recibimos una solicitud para cambiar tu contrase\u00f1a.</p>
                <p><a href="%s" style="display:inline-block;background:#111827;color:#ffffff;padding:12px 18px;text-decoration:none;border-radius:6px">Restablecer contrase\u00f1a</a></p>
                <p>Este enlace vence aproximadamente en %d minuto(s). Si no solicitaste este cambio, puedes ignorar este correo.</p>
                <p>Soporte: <a href="mailto:%s">%s</a></p>
                </body></html>
                """.formatted(escapeHtml(resetUrl), minutes,
                escapeHtml(properties.supportAddress()), escapeHtml(properties.supportAddress()));
        return new TransactionalEmail(recipient, subject, html, text, "password-reset-token-" + tokenId);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
