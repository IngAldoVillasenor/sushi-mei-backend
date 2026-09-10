package com.cardovia.merkon.backend.security;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Small provider-neutral MerkON verification-email renderer. */
@Component
class EmailVerificationEmailTemplate {

    private final TransactionalEmailProperties properties;
    private final Clock clock;

    EmailVerificationEmailTemplate(TransactionalEmailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    TransactionalEmail render(String recipient, String plaintextToken, Instant expiresAt, Long tokenId) {
        String verificationUrl = UriComponentsBuilder.fromUriString(properties.verificationBaseUrl())
                .replaceQueryParam("token", plaintextToken)
                .build()
                .toUriString();
        long hours = Math.max(1, Duration.between(clock.instant(), expiresAt).toHours());
        String subject = "Verifica tu correo de MerkON";
        String text = "Verifica tu correo para activar tu cuenta de MerkON.\n\n"
                + "Abre la aplicación o sitio de MerkON y usa este enlace para continuar:\n"
                + verificationUrl + "\n\n"
                + "El enlace vence aproximadamente en " + hours + " hora(s). "
                + "Si no solicitaste esta cuenta, puedes ignorar este correo.\n\n"
                + "Soporte: " + properties.supportAddress();
        String html = """
                <!doctype html>
                <html lang="es"><body style="font-family:Arial,sans-serif;color:#1f2937;line-height:1.5">
                <h1 style="font-size:22px">Verifica tu correo de MerkON</h1>
                <p>Confirma tu correo para activar tu cuenta.</p>
                <p><a href="%s" style="display:inline-block;background:#111827;color:#ffffff;padding:12px 18px;text-decoration:none;border-radius:6px">Verificar correo</a></p>
                <p>Este enlace vence aproximadamente en %d hora(s). Si no creaste esta cuenta, puedes ignorar este correo.</p>
                <p>Soporte: <a href="mailto:%s">%s</a></p>
                </body></html>
                """.formatted(escapeHtmlAttribute(verificationUrl), hours,
                escapeHtml(properties.supportAddress()), escapeHtml(properties.supportAddress()));
        return new TransactionalEmail(recipient, subject, html, text, "email-verification-token-" + tokenId);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeHtmlAttribute(String value) {
        return escapeHtml(value);
    }
}
