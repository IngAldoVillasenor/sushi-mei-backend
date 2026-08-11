package com.sushimei.sushimei.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionValidationFilter extends OncePerRequestFilter {

    private final SessionAuthenticationValidator validator;
    private final JsonAuthenticationEntryPoint entryPoint;

    public SessionValidationFilter(SessionAuthenticationValidator validator, JsonAuthenticationEntryPoint entryPoint) {
        this.validator = validator;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            var jwt = jwtAuthentication.getToken();
            boolean valid = validator.valid(
                    jwt.getSubject(),
                    jwt.getClaimAsString("sid"),
                    jwt.getClaimAsString("role"),
                    jwt.getClaimAsString("username"));
            if (!valid) {
                SecurityContextHolder.clearContext();
                entryPoint.commence(request, response, new BadCredentialsException("Invalid authoritative session"));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}