package com.sushimei.sushimei.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class JwtKeyConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    private final JwtKeyConfiguration configuration = new JwtKeyConfiguration();

    @Test
    void loadsMatchingPkcs8AndX509RsaKeysFromFileResources() throws Exception {
        KeyPair source = rsaKeyPair(2048);
        SushiMeiSecurityProperties properties = properties(writePem("private.pem", "PRIVATE KEY", source.getPrivate().getEncoded()),
                writePem("public.pem", "PUBLIC KEY", source.getPublic().getEncoded()));

        KeyPair loaded = configuration.securityRsaKeyPair(properties, new DefaultResourceLoader());

        assertThat(loaded.getPrivate().getEncoded()).isEqualTo(source.getPrivate().getEncoded());
        assertThat(loaded.getPublic().getEncoded()).isEqualTo(source.getPublic().getEncoded());
    }

    @Test
    void rejectsWeakOrMalformedFileKeysWithoutExposingTheirContents() throws Exception {
        KeyPair weak = rsaKeyPair(1024);
        SushiMeiSecurityProperties weakProperties = properties(
                writePem("weak-private.pem", "PRIVATE KEY", weak.getPrivate().getEncoded()),
                writePem("weak-public.pem", "PUBLIC KEY", weak.getPublic().getEncoded()));

        assertThatThrownBy(() -> configuration.securityRsaKeyPair(weakProperties, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 2048 bits")
                .hasMessageNotContaining("BEGIN");

        Path malformed = temporaryDirectory.resolve("malformed-private.pem");
        Files.writeString(malformed, "not a PEM");
        SushiMeiSecurityProperties malformedProperties = properties(malformed, temporaryDirectory.resolve("weak-public.pem"));
        assertThatThrownBy(() -> configuration.securityRsaKeyPair(malformedProperties, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8 private key");
    }

    @Test
    void rejectsPublicAndPrivateKeysFromDifferentRsaPairs() throws Exception {
        KeyPair publicPair = rsaKeyPair(2048);
        KeyPair privatePair = rsaKeyPair(2048);
        SushiMeiSecurityProperties properties = properties(
                writePem("private.pem", "PRIVATE KEY", privatePair.getPrivate().getEncoded()),
                writePem("public.pem", "PUBLIC KEY", publicPair.getPublic().getEncoded()));

        assertThatThrownBy(() -> configuration.securityRsaKeyPair(properties, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching RSA PKCS#8 private key")
                .hasRootCauseMessage("Configured JWT RSA public and private keys do not match");
    }

    private Path writePem(String fileName, String label, byte[] encoded) throws Exception {
        Path file = temporaryDirectory.resolve(fileName);
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        Files.writeString(file, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
        return file;
    }

    private static SushiMeiSecurityProperties properties(Path privateKey, Path publicKey) {
        return new SushiMeiSecurityProperties(
                new SushiMeiSecurityProperties.Jwt(privateKey.toUri().toString(), publicKey.toUri().toString(),
                        "test-kid", "urn:test:issuer", "urn:test:audience", Duration.ofMinutes(15)),
                Duration.ofDays(15), 4, null, null, null);
    }

    private static KeyPair rsaKeyPair(int bitLength) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bitLength);
        return generator.generateKeyPair();
    }
}
