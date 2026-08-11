package com.sushimei.sushimei.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonAuthenticationEntryPoint responseWriter;

    public JsonAccessDeniedHandler(JsonAuthenticationEntryPoint responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        responseWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                new SecurityApiError("AUTH_FORBIDDEN", "No tienes permisos para realizar esta operación."));
    }
}