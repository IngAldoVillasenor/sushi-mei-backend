package com.sushimei.sushimei.backend.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtKeyConfiguration {

    @Bean
    @Profile("!test")
    KeyPair securityRsaKeyPair(SushiMeiSecurityProperties properties, ResourceLoader resourceLoader) {
        String privateKeyLocation = properties.jwt().privateKeyLocation();
        String publicKeyLocation = properties.jwt().publicKeyLocation();
        if (isBlank(privateKeyLocation) || isBlank(publicKeyLocation)) {
            throw new IllegalStateException("JWT RSA private and public key locations must be configured");
        }
        try {
            RSAPublicKey publicKey = readPublicKey(resourceLoader.getResource(publicKeyLocation));
            RSAPrivateKey privateKey = readPrivateKey(resourceLoader.getResource(privateKeyLocation));
            requireMinimumStrength(publicKey);
            requireMinimumStrength(privateKey);
            requireMatchingPair(publicKey, privateKey);
            return new KeyPair(publicKey, privateKey);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "JWT RSA key configuration is invalid; configured files must contain a matching RSA PKCS#8 private key "
                            + "and X.509 public key of at least 2048 bits",
                    exception);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(KeyPair securityRsaKeyPair, SushiMeiSecurityProperties properties) {
        com.nimbusds.jose.jwk.RSAKey key = new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) securityRsaKeyPair.getPublic())
                .privateKey((RSAPrivateKey) securityRsaKeyPair.getPrivate())
                .keyID(properties.jwt().keyId())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(KeyPair securityRsaKeyPair, SushiMeiSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) securityRsaKeyPair.getPublic())
                .validateType(false)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> timestamps = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> issuer = jwt -> properties.jwt().issuer().equals(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.jwt().audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        OAuth2TokenValidator<Jwt> type = jwt -> "at+jwt".equals(jwt.getHeaders().get("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, issuer, audience, type));
        return decoder;
    }

    private static RSAPublicKey readPublicKey(Resource resource) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decodePem(resource)));
    }

    private static RSAPrivateKey readPrivateKey(Resource resource) throws Exception {
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decodePem(resource)));
    }

    private static byte[] decodePem(Resource resource) throws IOException {
        try (InputStream stream = resource.getInputStream()) {
            String pem = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
            return Base64.getMimeDecoder().decode(pem.replaceAll("-----[^-]+-----", ""));
        }
    }

    private static void requireMinimumStrength(java.security.interfaces.RSAKey key) {
        if (key.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("JWT RSA keys must be at least 2048 bits");
        }
    }

    private static void requireMatchingPair(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("Configured JWT RSA public and private keys do not match");
        }
        if (privateKey instanceof RSAPrivateCrtKey crtKey
                && !publicKey.getPublicExponent().equals(crtKey.getPublicExponent())) {
            throw new IllegalStateException("Configured JWT RSA public and private keys do not match");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
