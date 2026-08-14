package com.sushimei.sushimei.backend.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, SecurityIntegrationTest.TestInfrastructureConfiguration.class})
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private UserManagementService userManagementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SushiMeiSecurityProperties securityProperties;

    @BeforeEach
    void cleanSecurityFixtures() {
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.app_users");
    }

    @Test
    void loginUsesCanonicalUsernameAndReturnsExpectedRs256Claims() throws Exception {
        createUser("memo", "una frase larga segura 123", ApplicationRole.MANAGER);

        JsonNode response = login(" MEMO ", "una frase larga segura 123", "tablet-1");
        Jwt jwt = jwtDecoder.decode(response.required("accessToken").asText());

        assertThat(jwt.getHeaders())
                .containsEntry("alg", "RS256")
                .containsEntry("typ", "at+jwt")
                .containsKey("kid");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("urn:sushi-mei:test-auth");
        assertThat(jwt.getAudience()).contains("urn:sushi-mei:test-api");
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaimAsString("sid")).isNotBlank();
        assertThat(jwt.getClaimAsString("role")).isEqualTo("MANAGER");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("memo");
        assertThat(jwt.getIssuedAt()).isEqualTo(jwt.getNotBefore());
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(15 * 60L));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(response.required("refreshToken").asText()).startsWith("smr_");
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.auth_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.auth_sessions where current_refresh_token_hash = ?",
                Integer.class,
                response.required("refreshToken").asText())).isZero();
    }

    @Test
    void invalidUnknownAndInactiveCredentialsUseSamePublicErrorAndPersistAudit() throws Exception {
        AppUser inactive = createUser("inactiva", "una frase larga segura 123", ApplicationRole.CASHIER);
        inactive.update("Inactiva", ApplicationRole.CASHIER, false, clock.instant());
        userRepository.saveAndFlush(inactive);
        createUser("caja", "otra frase larga segura 123", ApplicationRole.CASHIER);

        assertInvalidLogin("caja", "incorrecta");
        assertInvalidLogin("desconocido", "incorrecta");
        assertInvalidLogin("inactiva", "una frase larga segura 123");

        assertThat(jdbcTemplate.queryForObject(
                "select failed_login_attempts from public.app_users where username = 'caja'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.security_audit_events where event_type = 'LOGIN_FAILURE'", Integer.class)).isEqualTo(3);
    }

    @Test
    void successfulLoginResetsFailureStateAndCommitsAuditWithLoginAttempt() throws Exception {
        AppUser account = createUser("caja", "otra frase larga segura 123", ApplicationRole.CASHIER);
        assertInvalidLogin("caja", "incorrecta");

        login("caja", "otra frase larga segura 123", "tablet");

        assertThat(jdbcTemplate.queryForObject(
                "select failed_login_attempts from public.app_users where id = ?", Integer.class, account.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select locked_until from public.app_users where id = ?", Instant.class, account.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.security_audit_events where event_type = 'LOGIN_SUCCESS'", Integer.class)).isEqualTo(1);
    }

    @Test
    void refreshRotatesTokenAndReplayRevocationAndAuditRemainCommitted() throws Exception {
        createUser("memo", "una frase larga segura 123", ApplicationRole.MANAGER);
        JsonNode login = login("memo", "una frase larga segura 123", "tablet-1");
        String firstRefresh = login.required("refreshToken").asText();
        Instant initialExpiry = Instant.parse(login.required("sessionExpiresAt").asText());

        JsonNode rotation = refresh(firstRefresh, "tablet-1", status().isOk());
        Instant storedAbsoluteExpiry = jdbcTemplate.queryForObject(
                "select absolute_expires_at from public.auth_sessions", Instant.class);
        assertThat(rotation.required("refreshToken").asText()).isNotEqualTo(firstRefresh);
        assertThat(Instant.parse(rotation.required("sessionExpiresAt").asText())).isEqualTo(storedAbsoluteExpiry);
        assertThat(storedAbsoluteExpiry).isAfter(initialExpiry.minus(ChronoUnit.MICROS.getDuration()));
        assertThat(storedAbsoluteExpiry).isBefore(initialExpiry.plus(ChronoUnit.MICROS.getDuration()));
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.auth_refresh_token_history", Integer.class)).isEqualTo(1);

        refresh(firstRefresh, "tablet-1", status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.auth_sessions where revoked_at is not null", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.security_audit_events where event_type = 'REFRESH_REPLAY_DETECTED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void logoutAndAuthoritativeSessionStateInvalidateExistingBearerImmediately() throws Exception {
        createUser("memo", "una frase larga segura 123", ApplicationRole.MANAGER);
        String accessToken = login("memo", "una frase larga segura 123", "tablet-1")
                .required("accessToken")
                .asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void ownerResponsesExposeActiveAndVersionAndOmittedVersionsAreRejected() throws Exception {
        AppUser target = createUser("caja", "una frase larga segura 123", ApplicationRole.CASHIER);
        createUser("owner", "una frase larga segura 123", ApplicationRole.OWNER);
        String ownerToken = login("owner", "una frase larga segura 123", "owner-tablet")
                .required("accessToken")
                .asText();
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("nuevo", "Nuevo", "contraseña unicode con espacio 123", ApplicationRole.KITCHEN));

        mockMvc.perform(post("/api/v1/security/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0));
        mockMvc.perform(post("/api/v1/security/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_USER"));
        mockMvc.perform(put("/api/v1/security/users/{id}", target.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Caja\",\"role\":\"CASHIER\",\"active\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/security/users/{id}/reset-password", target.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"contraseña unicode con espacio 123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lastActiveOwnerCannotBeDisabledOrDemotedButTwoOwnersPermitOneChange() {
        AppUser onlyOwner = createUser("owner-one", "una frase larga segura 123", ApplicationRole.OWNER);

        assertSecurityCode(() -> userManagementService.update(
                onlyOwner.getId(),
                new UpdateUserRequest("Owner", ApplicationRole.OWNER, false, onlyOwner.getVersion()),
                onlyOwner.getId(),
                "127.0.0.1"), "INVALID_USER");
        assertSecurityCode(() -> userManagementService.update(
                onlyOwner.getId(),
                new UpdateUserRequest("Owner", ApplicationRole.MANAGER, true, onlyOwner.getVersion()),
                onlyOwner.getId(),
                "127.0.0.1"), "INVALID_USER");

        AppUser secondOwner = createUser("owner-two", "una frase larga segura 123", ApplicationRole.OWNER);
        userManagementService.update(
                onlyOwner.getId(),
                new UpdateUserRequest("Owner", ApplicationRole.MANAGER, true, onlyOwner.getVersion()),
                secondOwner.getId(),
                "127.0.0.1");
        assertThat(userRepository.findById(onlyOwner.getId()).orElseThrow().getRole()).isEqualTo(ApplicationRole.MANAGER);
    }

    @Test
    void staleUserVersionIsRejectedDeterministically() {
        AppUser account = createUser("caja", "una frase larga segura 123", ApplicationRole.CASHIER);

        assertSecurityCode(() -> userManagementService.update(
                account.getId(),
                new UpdateUserRequest("Caja", ApplicationRole.CASHIER, true, account.getVersion() + 1),
                account.getId(),
                "127.0.0.1"), "USER_VERSION_CONFLICT");
    }

        @Test
    void decoderRejectsWrongIssuerAudienceTypeExpiredAndTamperedTokens() {
        Instant now = clock.instant();
        assertThatThrownBy(() -> jwtDecoder.decode(customToken("wrong-issuer", "urn:sushi-mei:test-api", "at+jwt", now, now.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jwtDecoder.decode(customToken("urn:sushi-mei:test-auth", "wrong-audience", "at+jwt", now, now.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jwtDecoder.decode(customToken("urn:sushi-mei:test-auth", "urn:sushi-mei:test-api", "JWT", now, now.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jwtDecoder.decode(customToken("urn:sushi-mei:test-auth", "urn:sushi-mei:test-api", "at+jwt", now.minusSeconds(180), now.minusSeconds(120))))
                .isInstanceOf(RuntimeException.class);

        String signed = customToken("urn:sushi-mei:test-auth", "urn:sushi-mei:test-api", "at+jwt", now, now.plusSeconds(60));
        assertThatThrownBy(() -> jwtDecoder.decode(signed + "x")).isInstanceOf(RuntimeException.class);
    }
@Test
    void passwordPolicyRejectsUnsafePasswordsAndAcceptsUnicodeSpaces() {
        assertPasswordRejected("memo", "corta");
        assertPasswordRejected("memo", "sushimei una frase suficientemente larga");
        assertPasswordRejected("memo", "memo es parte de una frase suficientemente larga");
        assertPasswordRejected("memo", "password123 password123");
        assertThat(passwordPolicyService.encodeValidated("memo", "Frase segura con ñ y espacios 123"))
                .startsWith("{bcrypt}");
    }

    @Test
    void authorizationMatrixAndPublicRoutesRemainNarrow() throws Exception {
        mockMvc.perform(get("/api/v1/menu/items")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/menu/items").with(user("cashier").roles("CASHIER"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/menu/items").with(user("cashier").roles("CASHIER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/menu/items").with(user("manager").roles("MANAGER"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/promotions/active")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/promotions/active").with(user("owner").roles("OWNER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/promotions/active").with(user("manager").roles("MANAGER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/promotions/active").with(user("cashier").roles("CASHIER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/promotions/active").with(user("kitchen").roles("KITCHEN"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/promotions").with(user("cashier").roles("CASHIER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/promotions/quote").with(user("cashier").roles("CASHIER"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/promotions").with(user("cashier").roles("CASHIER"))).andExpect(status().isForbidden());
        createUser("manual-cashier", "una frase larga segura 123", ApplicationRole.CASHIER);
        createUser("manual-manager", "una frase larga segura 123", ApplicationRole.MANAGER);
        createUser("manual-owner", "una frase larga segura 123", ApplicationRole.OWNER);
        String cashierToken = login("manual-cashier", "una frase larga segura 123", "manual-cashier-device")
                .required("accessToken").asText();
        String managerToken = login("manual-manager", "una frase larga segura 123", "manual-manager-device")
                .required("accessToken").asText();
        String ownerToken = login("manual-owner", "una frase larga segura 123", "manual-owner-device")
                .required("accessToken").asText();
        String manualOrder = """
                {"requestId":"00000000-0000-0000-0000-000000000123","fulfillmentType":"PICKUP",
                "paymentMethod":"CASH","pickupName":"Ana","cashDenomination":100.00,
                "lines":[{"lineKey":"line","menuItemId":99999,"quantity":1,"groups":[],"rewardConfigurations":[]}]}
                """;
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(manualOrder))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/orders").with(user("kitchen").roles("KITCHEN"))
                        .contentType(MediaType.APPLICATION_JSON).content(manualOrder))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(manualOrder))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(manualOrder))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(manualOrder))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/orders/active").with(user("kitchen").roles("KITCHEN"))).andExpect(status().isOk());
        mockMvc.perform(put("/api/orders/1/prepare").with(user("kitchen").roles("KITCHEN"))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/orders/1/validate-payment").with(user("kitchen").roles("KITCHEN"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/security/users").with(user("cashier").roles("CASHIER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/whatsapp/webhook")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/whatsapp/webhook").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/uploads/receipt.png")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/dev/ai/chat").with(user("cashier").roles("CASHIER"))).andExpect(status().isForbidden());
    }

        private String customToken(String issuer, String audience, String type, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(java.util.List.of(audience))
                .subject("1")
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id("test-jti")
                .claim("sid", "00000000-0000-0000-0000-000000000001")
                .claim("role", "CASHIER")
                .claim("username", "caja")
                .build();
        JwsHeader header = JwsHeader.with(() -> "RS256")
                .type(type)
                .keyId(securityProperties.jwt().keyId())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
private AppUser createUser(String username, String password, ApplicationRole role) {
        return userRepository.saveAndFlush(AppUser.create(
                username,
                username,
                passwordPolicyService.encodeValidated(username, password),
                role,
                clock.instant()));
    }

    private JsonNode login(String username, String password, String deviceId) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(username, password, deviceId, "Device", "1.0"));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertInvalidLogin(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(username, password, "device", null, null));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Usuario o contraseña incorrectos."));
    }

    private JsonNode refresh(String refreshToken, String deviceId, ResultMatcher expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(new RefreshRequest(refreshToken, deviceId));
        String response = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expectedStatus)
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertPasswordRejected(String username, String password) {
        assertSecurityCode(() -> passwordPolicyService.encodeValidated(username, password), "AUTH_PASSWORD_REJECTED");
    }

    private void assertSecurityCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String code) {
        org.assertj.core.api.Assertions.assertThatThrownBy(operation)
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo(code);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
