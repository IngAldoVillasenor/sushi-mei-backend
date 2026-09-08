package com.cardovia.merkon.backend.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenServiceTest {

    private final RefreshTokenService service = new RefreshTokenService();

    @Test
    void issuesMerkonTokensAndAcceptsBothMerkonAndLegacyPrefixes() {
        UUID sessionId = UUID.randomUUID();
        String issued = service.issue(sessionId);

        assertThat(issued).startsWith("mkr_");
        assertThat(service.parse(issued)).isNotNull();
        assertThat(service.parse("mkr_" + sessionId + ".secret")).isNotNull();
        assertThat(service.parse("smr_" + sessionId + ".secret")).isNotNull();
        assertThat(service.parse("other_" + sessionId + ".secret")).isNull();
    }
}
