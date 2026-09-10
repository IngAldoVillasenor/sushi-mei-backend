package com.cardovia.merkon.backend.security;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Resend REST adapter. No account lifecycle service depends on this provider type. */
final class ResendTransactionalEmailSender implements TransactionalEmailSender {

    private static final String USER_AGENT = "MerkON Backend/1.0";
    private static final String OFFICIAL_RESEND_API_BASE_URL = "https://api.resend.com";

    private final RestClient client;
    private final TransactionalEmailProperties properties;

    ResendTransactionalEmailSender(RestClient.Builder builder, TransactionalEmailProperties properties) {
        this(pinnedResendClient(builder, properties), properties);
    }

    private static RestClient pinnedResendClient(RestClient.Builder builder, TransactionalEmailProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        // Keep the bearer credential pinned to Resend's documented HTTPS host.
        // Tests inject a local RestClient through the package-private constructor.
        return builder.baseUrl(OFFICIAL_RESEND_API_BASE_URL).requestFactory(requestFactory).build();
    }

    ResendTransactionalEmailSender(RestClient client, TransactionalEmailProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void send(TransactionalEmail email) {
        try {
            client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("User-Agent", USER_AGENT)
                    .header("Idempotency-Key", email.idempotencyKey())
                    .body(new ResendEmailRequest(
                            properties.fromName() + " <" + properties.fromAddress() + ">",
                            List.of(email.recipient()), email.subject(), email.htmlBody(), email.textBody()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new TransactionalEmailDeliveryException("RESEND_REQUEST_FAILED", exception);
        }
    }

    private record ResendEmailRequest(String from, List<String> to, String subject, String html, String text) {
    }
}
