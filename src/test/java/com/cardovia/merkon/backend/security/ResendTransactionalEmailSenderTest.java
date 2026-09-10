package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ResendTransactionalEmailSenderTest {

    private final AtomicReference<HttpExchange> request = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsExpectedResendPayloadAuthorizationAndIdempotencyHeader() throws Exception {
        ResendTransactionalEmailSender sender = sender();
        sender.send(new TransactionalEmail(
                "owner@example.com", "Verify MerkON", "<p>verify</p>", "verify", "email-verification-token-42"));

        HttpExchange exchange = request.get();
        assertThat(exchange.getRequestURI().getPath()).isEqualTo("/emails");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-api-key");
        assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("email-verification-token-42");
        assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).contains("MerkON Backend/");
        JsonNode payload = new ObjectMapper().readTree(body.get());
        assertThat(payload.get("from").asText()).isEqualTo("MerkON <no-reply@example.com>");
        assertThat(payload.get("to")).extracting(JsonNode::asText).containsExactly("owner@example.com");
        assertThat(payload.get("subject").asText()).isEqualTo("Verify MerkON");
    }

    @Test
    void classifiesProviderFailureWithoutExposingProviderResponse() throws Exception {
        for (int providerStatus : List.of(400, 503)) {
            status.set(providerStatus);
            ResendTransactionalEmailSender sender = sender();

            assertThatThrownBy(() -> sender.send(new TransactionalEmail(
                    "owner@example.com", "Verify MerkON", "<p>verify</p>", "verify", "email-verification-token-43")))
                    .isInstanceOf(TransactionalEmailDeliveryException.class)
                    .hasMessage("RESEND_REQUEST_FAILED");
            stopServer();
            server = null;
        }
    }

    private ResendTransactionalEmailSender sender() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", this::respond);
        server.start();
        int port = server.getAddress().getPort();
        TransactionalEmailProperties properties = new TransactionalEmailProperties(
                TransactionalEmailProperties.Provider.RESEND,
                false,
                "test-api-key",
                "no-reply@example.com",
                "MerkON",
                "https://verify.example.com/email",
                "support@example.com",
                "http://127.0.0.1:" + port,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
        return new ResendTransactionalEmailSender(RestClient.builder().baseUrl("http://127.0.0.1:" + port).build(), properties);
    }

    private void respond(HttpExchange exchange) throws IOException {
        request.set(exchange);
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = "{\"id\":\"test-email\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
