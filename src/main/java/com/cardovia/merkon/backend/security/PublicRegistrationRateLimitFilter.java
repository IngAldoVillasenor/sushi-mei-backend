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
 * Counts every public registration POST before MVC validation or body parsing.
 * It intentionally ignores forwarded headers; see RegistrationClientAddressResolver.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class PublicRegistrationRateLimitFilter extends OncePerRequestFilter {

    private static final String REGISTRATION_PATH = "/api/v1/registration";

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
        return !"POST".equals(request.getMethod()) || !REGISTRATION_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String observedConnectionAddress = clientAddresses.resolve(request);
        try {
            rateLimit.checkTransportAddress(observedConnectionAddress);
        } catch (SecurityApiException exception) {
            if (!"REGISTRATION_RATE_LIMITED".equals(exception.code())) {
                throw exception;
            }
            audit.record(
                    SecurityAuditEventType.REGISTRATION_REJECTED,
                    null,
                    null,
                    null,
                    null,
                    observedConnectionAddress,
                    SecurityAuditOutcome.FAILURE,
                    "RATE_LIMITED");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    new SecurityApiError(exception.code(), exception.getMessage()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
