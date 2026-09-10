package com.cardovia.merkon.backend.security;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for application-owned transactional email delivery. */
@ConfigurationProperties(prefix = "merkon.transactional-email")
public record TransactionalEmailProperties(
        Provider provider,
        boolean enabled,
        String apiKey,
        String fromAddress,
        String fromName,
        String verificationBaseUrl,
        String supportAddress,
        String resendApiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public enum Provider {
        DISABLED,
        RESEND
    }

    public TransactionalEmailProperties {
        provider = provider == null ? Provider.DISABLED : provider;
        fromName = requireText(fromName, "merkon.transactional-email.from-name", 120);
        URI verificationUrl = requireHttpUrl(verificationBaseUrl, "merkon.transactional-email.verification-base-url");
        URI resendUrl = requireHttpUrl(resendApiBaseUrl, "merkon.transactional-email.resend-api-base-url");
        verificationBaseUrl = verificationUrl.toString();
        resendApiBaseUrl = resendUrl.toString();
        connectTimeout = requireTimeout(connectTimeout, "merkon.transactional-email.connect-timeout");
        readTimeout = requireTimeout(readTimeout, "merkon.transactional-email.read-timeout");
        if (enabled) {
            if (provider != Provider.RESEND) {
                throw new IllegalArgumentException("An enabled transactional-email provider must be RESEND");
            }
            requireConfiguredAddress(fromAddress, "merkon.transactional-email.from-address");
            requireConfiguredAddress(supportAddress, "merkon.transactional-email.support-address");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Transactional email is enabled but its API credential is missing");
            }
            if (!"https".equalsIgnoreCase(verificationUrl.getScheme()) || isPlaceholderUrl(verificationUrl)) {
                throw new IllegalArgumentException("Transactional email is enabled but its verification base URL is not configured");
            }
            requireOfficialResendUrl(resendUrl);
        }
    }

    private static String requireText(String value, String property, int maximumLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(property + " must be nonblank and no more than " + maximumLength + " characters");
        }
        return normalized;
    }

    private static URI requireHttpUrl(String value, String property) {
        String normalized = value == null ? "" : value.strip();
        URI uri;
        try {
            uri = URI.create(normalized);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(property + " must be an absolute HTTP(S) URL");
        }
        return uri;
    }

    private static void requireConfiguredAddress(String value, String property) {
        String normalized = value == null ? "" : value.strip();
        String canonical = PublicEmailIdentity.orNull(normalized) == null ? null
                : PublicEmailIdentity.require(normalized).canonicalAscii();
        if (canonical == null || canonical.substring(canonical.lastIndexOf('@') + 1)
                .toLowerCase(Locale.ROOT).endsWith(".invalid")) {
            throw new IllegalArgumentException(property + " must be a configured email address");
        }
    }

    private static boolean isPlaceholderUrl(URI value) {
        return value.getHost().toLowerCase(Locale.ROOT).endsWith(".invalid");
    }

    private static void requireOfficialResendUrl(URI value) {
        if (!"https".equalsIgnoreCase(value.getScheme())
                || !"api.resend.com".equalsIgnoreCase(value.getHost())
                || (value.getPort() != -1 && value.getPort() != 443)) {
            throw new IllegalArgumentException("Transactional email is enabled but its Resend API URL is not configured securely");
        }
    }

    private static Duration requireTimeout(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(property + " must be positive and no more than one minute");
        }
        return value;
    }
}
