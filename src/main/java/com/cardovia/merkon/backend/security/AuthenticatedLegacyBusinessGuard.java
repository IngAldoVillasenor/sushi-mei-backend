package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Authorizes retained authenticated Sushi Mei-only routes without allowing a
 * caller to select or substitute the legacy business.
 */
@Component
public class AuthenticatedLegacyBusinessGuard {

    private final TrustedBusinessContext trustedBusinessContext;
    private final LegacyBusinessResolver legacyBusinessResolver;

    public AuthenticatedLegacyBusinessGuard(TrustedBusinessContext trustedBusinessContext,
                                            LegacyBusinessResolver legacyBusinessResolver) {
        this.trustedBusinessContext = Objects.requireNonNull(trustedBusinessContext,
                "trustedBusinessContext must not be null");
        this.legacyBusinessResolver = Objects.requireNonNull(legacyBusinessResolver,
                "legacyBusinessResolver must not be null");
    }

    public void requireLegacyBusiness(Jwt jwt) {
        Long authenticatedBusinessId = trustedBusinessContext.requireBusinessId(jwt);
        Long legacyBusinessId = legacyBusinessResolver.requireLegacyBusinessId();
        if (!legacyBusinessId.equals(authenticatedBusinessId)) {
            throw new SecurityApiException(
                    "AUTH_BUSINESS_FORBIDDEN",
                    HttpStatus.FORBIDDEN,
                    "La sesión no tiene acceso al negocio legado.");
        }
    }
}
