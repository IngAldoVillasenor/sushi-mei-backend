package com.sushimei.sushimei.backend.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final SushiMeiSecurityProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder jwtEncoder, SushiMeiSecurityProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public Issued issue(AppUser user, AuthSession session, Instant now) {
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        if (expiresAt.isAfter(session.getAbsoluteExpiresAt())) {
            expiresAt = session.getAbsoluteExpiresAt();
        }

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("sid", session.getId().toString())
                .claim("role", user.getRole().name())
                .claim("username", user.getUsername())
                .build();
        JwsHeader header = JwsHeader.with(() -> "RS256")
                .type("at+jwt")
                .keyId(properties.jwt().keyId())
                .build();
        String encoded = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Issued(encoded, expiresAt);
    }

    public record Issued(String value, Instant expiresAt) {
    }
}