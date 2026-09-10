package com.cardovia.merkon.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Returns the server-observed transport peer for coarse registration abuse
 * control. Forwarded headers are deliberately ignored: this deployment has no
 * configured trusted-proxy boundary that can prove an X-Forwarded-For value
 * was added by infrastructure rather than supplied by a caller.
 */
@Component
class RegistrationClientAddressResolver {

    String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null ? "" : remoteAddress.strip();
    }
}
