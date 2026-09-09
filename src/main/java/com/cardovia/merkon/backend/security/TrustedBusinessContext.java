package com.cardovia.merkon.backend.security;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Extracts only the already-validated active-business claim from a JWT. */
@Component
public class TrustedBusinessContext {

    public Long requireBusinessId(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        try {
            String claim = jwt.getClaimAsString("bid");
            long businessId = Long.parseLong(claim);
            if (businessId <= 0) {
                throw new NumberFormatException("business id must be positive");
            }
            return businessId;
        } catch (RuntimeException exception) {
            throw new SecurityApiException(
                    "AUTH_BUSINESS_FORBIDDEN",
                    HttpStatus.FORBIDDEN,
                    "No hay un negocio activo autorizado para esta sesión.");
        }
    }
}
