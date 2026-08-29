package com.sushimei.sushimei.backend.security;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder(SushiMeiSecurityProperties properties) {
        Map<String, PasswordEncoder> encoders = Map.of(
                "bcrypt", new BCryptPasswordEncoder(properties.bcryptStrength()));
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return new ApplicationRoleJwtAuthenticationConverter();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JsonAuthenticationEntryPoint entryPoint,
                                            JsonAccessDeniedHandler accessDeniedHandler,
                                            SessionValidationFilter sessionValidationFilter,
                                            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        // The only anonymous application endpoints are login, refresh, and Meta's existing webhook.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/whatsapp/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/whatsapp/webhook").permitAll()
                        .requestMatchers("/internal/**").hasRole("OWNER")
                        .requestMatchers("/api/v1/security/**").hasRole("OWNER")
                        .requestMatchers("/api/v1/auth/**").authenticated()
                        .requestMatchers("/api/v1/menu/items/*/configuration-definition").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/v1/menu/items/*/quote", "/api/v1/promotions/quote")
                        .hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers("/api/v1/menu/items/*/configuration").hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/menu/items/**").authenticated()
                        .requestMatchers("/api/v1/menu/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/promotions/active")
                        .hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers("/api/v1/promotions/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/v1/business-days/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/analytics", "/api/v1/orders").hasAnyRole("OWNER", "MANAGER").requestMatchers(HttpMethod.GET, "/api/v1/orders/active", "/api/v1/orders/*")
                        .hasAnyRole("OWNER", "MANAGER", "CASHIER", "KITCHEN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders")
                        .hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/open-sales")
                        .hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers("/api/orders/*/validate-payment").hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers("/api/orders/*/void").hasAnyRole("OWNER", "MANAGER", "CASHIER")
                        .requestMatchers("/api/orders/*/prepare", "/api/orders/*/ready", "/api/orders/*/complete", "/api/orders/*/reject")
                        .hasAnyRole("OWNER", "MANAGER", "KITCHEN")
                        .requestMatchers("/api/orders/active").hasAnyRole("OWNER", "MANAGER", "CASHIER", "KITCHEN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint))
                .addFilterAfter(sessionValidationFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    private static final class ApplicationRoleJwtAuthenticationConverter
            implements Converter<Jwt, AbstractAuthenticationToken> {

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            String roleClaim = jwt.getClaimAsString("role");
            ApplicationRole role;
            try {
                role = ApplicationRole.valueOf(roleClaim);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new InvalidBearerTokenException("Invalid application role");
            }
            return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        }
    }
}
