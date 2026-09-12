package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Renders account-deletion mail using the centralized hardened URL configuration. */
@Component
class AccountDeletionEmailTemplate {

    private final TransactionalEmailProperties properties;
    private final Clock clock;

    AccountDeletionEmailTemplate(TransactionalEmailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    TransactionalEmail render(String recipient, String token, Instant expiresAt, Object id) {
        // A fragment is intentionally not transmitted in the page GET. The
        // user must explicitly POST the token after reviewing the page.
        String url = properties.accountDeletionBaseUrl() + "#token=" + token;
        long minutes = Math.max(1, Duration.between(clock.instant(), expiresAt).toMinutes());
        String text = "Recibimos una solicitud de eliminación de cuenta y datos de MerkON.\n\n"
                + "Abrir el enlace no elimina nada. Se requiere confirmación explícita para completar la eliminación.\n\n"
                + url + "\n\n"
                + "El enlace vence aproximadamente en " + minutes + " minuto(s). "
                + "Si no solicitaste esto, ignora este correo.\n\n"
                + "Soporte: " + properties.supportAddress();
        String html = """
                <!doctype html>
                <html lang="es"><body style="font-family:Arial,sans-serif;color:#1f2937;line-height:1.5">
                <h1 style="font-size:22px">Confirma la eliminación de tu cuenta MerkON</h1>
                <p>Recibimos una solicitud de eliminación de cuenta y datos.</p>
                <p><a href="%s" style="display:inline-block;background:#111827;color:#ffffff;padding:12px 18px;text-decoration:none;border-radius:6px">Revisar solicitud</a></p>
                <p>Abrir el enlace no elimina nada. Se requiere confirmación explícita para completar la eliminación.</p>
                <p>Este enlace vence aproximadamente en %d minuto(s). Si no solicitaste esto, ignora este correo.</p>
                <p>Soporte: <a href="mailto:%s">%s</a></p>
                </body></html>
                """.formatted(escapeHtml(url), minutes,
                escapeHtml(properties.supportAddress()), escapeHtml(properties.supportAddress()));
        return new TransactionalEmail(
                recipient,
                "Confirma la eliminación de tu cuenta MerkON",
                html,
                text,
                "account-deletion-" + id);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
