package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TransactionalEmailPropertiesTest {

    @Test
    void enabledResendFailsConfigurationWithoutCredentialOrVerifiedSenderConfiguration() {
        assertThatThrownBy(() -> new TransactionalEmailProperties(
                TransactionalEmailProperties.Provider.RESEND,
                true,
                "",
                "no-reply@merkon.invalid",
                "MerkON",
                "https://verification.merkon.invalid/verify-email",
                "https://password-reset.merkon.invalid/reset-password",
                "https://account-deletion.merkon.invalid/account-deletion",
                "support@merkon.invalid",
                "https://api.resend.com",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("test-api-key"));
    }

    @Test
    void enabledResendRequiresHttpsVerificationUrlAndOfficialHttpsApiHost() {
        assertThatThrownBy(() -> enabled("http://public.example.com/verify-email", "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("https://public.example.com/verify-email", "http://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("https://public.example.com/verify-email", "https://attacker.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("HTTPS://VERIFICATION.MERKON.INVALID/verify-email", "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledResendRequiresAnHttpsNonPlaceholderPasswordResetUrl() {
        assertThatThrownBy(() -> enabled(
                "https://verification.example.com/verify-email",
                "http://public.example.com/reset-password",
                "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled(
                "https://verification.example.com/verify-email",
                "https://password-reset.merkon.invalid/reset-password",
                "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledResendRequiresAnHttpsNonPlaceholderAccountDeletionUrl() {
        assertThat(enabled(
                "https://verification.example.com/verify-email",
                "https://reset.example.com/password",
                "https://delete.example.com/account-deletion",
                "https://api.resend.com").accountDeletionBaseUrl())
                .isEqualTo("https://delete.example.com/account-deletion");
        assertThatThrownBy(() -> enabled(
                "https://verification.example.com/verify-email",
                "https://reset.example.com/password",
                "http://delete.example.com/account-deletion",
                "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled(
                "https://verification.example.com/verify-email",
                "https://reset.example.com/password",
                "https://account-deletion.merkon.invalid/account-deletion",
                "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled(
                "https://verification.example.com/verify-email",
                "https://reset.example.com/password",
                "not-a-url",
                "https://api.resend.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledEmailAllowsTheSafeInvalidDeletionPlaceholder() {
        TransactionalEmailProperties properties = new TransactionalEmailProperties(
                TransactionalEmailProperties.Provider.DISABLED,
                false,
                "",
                "no-reply@merkon.invalid",
                "MerkON",
                "https://verification.merkon.invalid/verify-email",
                "https://password-reset.merkon.invalid/reset-password",
                "https://account-deletion.merkon.invalid/account-deletion",
                "support@merkon.invalid",
                "https://api.resend.com",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
        assertThat(properties.accountDeletionBaseUrl())
                .isEqualTo("https://account-deletion.merkon.invalid/account-deletion");
    }

    private static TransactionalEmailProperties enabled(String verificationBaseUrl, String resendApiBaseUrl) {
        return enabled(verificationBaseUrl, "https://reset.example.com/password",
                "https://delete.example.com/account-deletion", resendApiBaseUrl);
    }

    private static TransactionalEmailProperties enabled(String verificationBaseUrl,
                                                        String passwordResetBaseUrl,
                                                        String resendApiBaseUrl) {
        return enabled(verificationBaseUrl, passwordResetBaseUrl,
                "https://delete.example.com/account-deletion", resendApiBaseUrl);
    }

    private static TransactionalEmailProperties enabled(String verificationBaseUrl,
                                                        String passwordResetBaseUrl,
                                                        String accountDeletionBaseUrl,
                                                        String resendApiBaseUrl) {
        return new TransactionalEmailProperties(
                TransactionalEmailProperties.Provider.RESEND,
                true,
                "test-api-key",
                "no-reply@example.com",
                "MerkON",
                verificationBaseUrl,
                passwordResetBaseUrl,
                accountDeletionBaseUrl,
                "support@example.com",
                resendApiBaseUrl,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }
}
