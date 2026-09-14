package com.cardovia.merkon.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts every public registration/verification POST before MVC validation or body parsing.
 * It intentionally ignores forwarded headers; see RegistrationClientAddressResolver.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class PublicRegistrationRateLimitFilter extends OncePerRequestFilter {

    private static final String REGISTRATION_PATH = "/api/v1/registration";
    private static final String VERIFY_PATH = "/api/v1/registration/email-verification/verify";
    private static final String RESEND_PATH = "/api/v1/registration/email-verification/resend";
    private static final String PASSWORD_RECOVERY_REQUEST_PATH = "/api/v1/auth/password-recovery/request";
    private static final String PASSWORD_RECOVERY_CONFIRM_PATH = "/api/v1/auth/password-recovery/confirm";
    private static final String ACCOUNT_DELETION_REQUEST_PATH = "/api/v1/account-deletion/request";
    private static final String ACCOUNT_DELETION_CONFIRM_PATH = "/api/v1/account-deletion/confirm";

    private final RegistrationRateLimitService rateLimit;
    private final RegistrationClientAddressResolver clientAddresses;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;

    PublicRegistrationRateLimitFilter(RegistrationRateLimitService rateLimit,
                                      RegistrationClientAddressResolver clientAddresses,
                                      SecurityAuditService audit,
                                      ObjectMapper objectMapper) {
        this.rateLimit = rateLimit;
        this.clientAddresses = clientAddresses;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || route(request) == PublicRoute.NONE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String observedConnectionAddress = clientAddresses.resolve(request);
        PublicRoute route = route(request);
        try {
            switch (route) {
                case REGISTRATION -> rateLimit.checkTransportAddress(observedConnectionAddress);
                case VERIFY, RESEND -> rateLimit.checkEmailVerificationTransport(observedConnectionAddress);
                case PASSWORD_RECOVERY_REQUEST, PASSWORD_RECOVERY_CONFIRM ->
                        rateLimit.checkPasswordRecoveryTransport(observedConnectionAddress);
                case ACCOUNT_DELETION_REQUEST, ACCOUNT_DELETION_CONFIRM -> rateLimit.checkAccountDeletionTransport(observedConnectionAddress);
                case NONE -> throw new IllegalStateException("Unexpected public route");
            }
        } catch (SecurityApiException exception) {
            if (!isExpectedRateLimit(route, exception.code())) {
                throw exception;
            }
            if (route == PublicRoute.REGISTRATION) {
                audit.record(
                        SecurityAuditEventType.REGISTRATION_REJECTED,
                        null,
                        null,
                        null,
                        null,
                        observedConnectionAddress,
                        SecurityAuditOutcome.FAILURE,
                        "RATE_LIMITED");
            }
            // Email-verification coarse transport denials deliberately avoid a
            // per-request audit row: otherwise a rejected bot can turn the
            // audit table itself into an unbounded public write target.
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    new SecurityApiError(exception.code(), exception.getMessage()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static PublicRoute route(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case REGISTRATION_PATH -> PublicRoute.REGISTRATION;
            case VERIFY_PATH -> PublicRoute.VERIFY;
            case RESEND_PATH -> PublicRoute.RESEND;
            case PASSWORD_RECOVERY_REQUEST_PATH -> PublicRoute.PASSWORD_RECOVERY_REQUEST;
            case PASSWORD_RECOVERY_CONFIRM_PATH -> PublicRoute.PASSWORD_RECOVERY_CONFIRM;
            case ACCOUNT_DELETION_REQUEST_PATH -> PublicRoute.ACCOUNT_DELETION_REQUEST;
            case ACCOUNT_DELETION_CONFIRM_PATH -> PublicRoute.ACCOUNT_DELETION_CONFIRM;
            default -> PublicRoute.NONE;
        };
    }

    private static boolean isExpectedRateLimit(PublicRoute route, String code) {
        return (route == PublicRoute.REGISTRATION && "REGISTRATION_RATE_LIMITED".equals(code))
                || ((route == PublicRoute.VERIFY || route == PublicRoute.RESEND)
                && "EMAIL_VERIFICATION_TRANSPORT_RATE_LIMITED".equals(code))
                || ((route == PublicRoute.PASSWORD_RECOVERY_REQUEST || route == PublicRoute.PASSWORD_RECOVERY_CONFIRM)
                && "PASSWORD_RECOVERY_TRANSPORT_RATE_LIMITED".equals(code))
                || ((route == PublicRoute.ACCOUNT_DELETION_REQUEST || route == PublicRoute.ACCOUNT_DELETION_CONFIRM)
                && "ACCOUNT_DELETION_TRANSPORT_RATE_LIMITED".equals(code));
    }

    private enum PublicRoute {
        REGISTRATION,
        VERIFY,
        RESEND,
        PASSWORD_RECOVERY_REQUEST,
        PASSWORD_RECOVERY_CONFIRM,
        ACCOUNT_DELETION_REQUEST,
        ACCOUNT_DELETION_CONFIRM,
        NONE
    }
}
