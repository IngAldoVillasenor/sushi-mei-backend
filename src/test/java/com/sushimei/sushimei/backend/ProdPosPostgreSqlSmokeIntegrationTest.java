package com.sushimei.sushimei.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.configuration.WebConfig;
import com.sushimei.sushimei.backend.security.AppUserRepository;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod-pos")
class ProdPosPostgreSqlSmokeIntegrationTest {

    private static final String OWNER_USERNAME = "postgres-smoke-owner";
    private static final String OWNER_PASSWORD = "Frase de prueba segura PostgreSQL 2026";
    private static final PemFiles PEM_FILES = PemFiles.create();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("sushimei_smoke")
            .withUsername("sushimei_smoke")
            .withPassword("sushimei_smoke_password");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void productionLikeProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("SUSHIMEI_JWT_PRIVATE_KEY_LOCATION", () -> PEM_FILES.privateKeyFile().toUri().toString());
        registry.add("SUSHIMEI_JWT_PUBLIC_KEY_LOCATION", () -> PEM_FILES.publicKeyFile().toUri().toString());
        registry.add("SUSHIMEI_BOOTSTRAP_OWNER_USERNAME", () -> OWNER_USERNAME);
        registry.add("SUSHIMEI_BOOTSTRAP_OWNER_PASSWORD", () -> OWNER_PASSWORD);
        registry.add("SUSHIMEI_BOOTSTRAP_OWNER_DISPLAY_NAME", () -> "PostgreSQL Smoke Owner");
    }

    @AfterAll
    static void removeTemporaryKeys() throws IOException {
        Files.deleteIfExists(PEM_FILES.privateKeyFile());
        Files.deleteIfExists(PEM_FILES.publicKeyFile());
        Files.deleteIfExists(PEM_FILES.directory());
    }

    @Test
    void prodPosBootstrapsAStandardEmptyPostgreSqlDatabaseWithFileJwtKeysAndOwnerLogin() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("show server_version", String.class)).startsWith("17.");
        assertThat(environment.getProperty("DB_URL")).isEqualTo(POSTGRES.getJdbcUrl());
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.flyway_schema_history where success", Integer.class)).isEqualTo(22);
        assertThat(jdbcTemplate.queryForList(
                        "select script from public.flyway_schema_history where success order by installed_rank",
                        String.class))
                .containsExactly(
                        "B1__current_application_schema.sql",
                        "V2__add_parallel_numeric_money_columns.sql",
                        "V3__backfill_and_constrain_numeric_money.sql",
                        "V4__add_whatsapp_inbound_message_idempotency.sql",
                        "V5__add_structured_order_foundations.sql",
                        "V6__add_operational_menu_catalog.sql",
                        "V7__add_configurable_catalog_domain.sql",
                        "V8__add_temporal_promotions.sql",
                        "V9__add_application_security.sql",
                        "V10__add_manual_pos_order_foundations.sql",
                        "V11__add_authoritative_catalog_rules.sql",
                        "V12__add_authoritative_promotion_rules.sql",
                        "V13__repair_classic_roll_promotion_targets.sql",
                        "V14__persist_whatsapp_inbound_failure_diagnostics.sql",
                        "V15__add_flexible_promotion_rewards.sql",
                        "V16__add_historical_order_provenance.sql",
                        "V17__add_vendis_historical_sales_import.sql",
                        "V18__add_business_day_cash_reconciliation.sql",
                        "V19__add_business_day_reopen_history.sql",
                        "V20__add_order_flexibility.sql",
                        "V21__enforce_unique_promotion_targets.sql",
                        "V22__add_nested_customization_and_manual_priced_lines.sql");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.flyway_schema_history where success and version = '22'", Integer.class)).isOne();

        assertThat(userRepository.count()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where role = 'OWNER'", Integer.class)).isOne();
        String passwordHash = jdbcTemplate.queryForObject(
                "select password_hash from public.app_users where username = ?", String.class, OWNER_USERNAME);
        assertThat(passwordHash).startsWith("{bcrypt}").isNotEqualTo(OWNER_PASSWORD);

        JsonNode login = login();
        String accessToken = login.required("accessToken").asText();
        Jwt jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(OWNER_USERNAME))
                .andExpect(jsonPath("$.role").value("OWNER"));

        assertThat(applicationContext.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AiConversationService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WhatsAppService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WebConfig.class)).isEmpty();
        assertThat(environment.getProperty("storage.receipts-directory")).isEmpty();
        assertThat(environment.getProperty("storage.public-upload-directory")).isEmpty();
    }

    private JsonNode login() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", OWNER_USERNAME,
                                "password", OWNER_PASSWORD,
                                "deviceId", "postgres-smoke-device",
                                "deviceName", "PostgreSQL smoke test",
                                "appVersion", "test"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private record PemFiles(Path directory, Path privateKeyFile, Path publicKeyFile) {

        static PemFiles create() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keys = generator.generateKeyPair();
                Path directory = Files.createTempDirectory("sushimei-prod-pos-postgres-smoke-");
                Path privateKey = directory.resolve("private.pem");
                Path publicKey = directory.resolve("public.pem");
                Files.writeString(privateKey, pem("PRIVATE KEY", keys.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
                Files.writeString(publicKey, pem("PUBLIC KEY", keys.getPublic().getEncoded()), StandardCharsets.US_ASCII);
                privateKey.toFile().deleteOnExit();
                publicKey.toFile().deleteOnExit();
                directory.toFile().deleteOnExit();
                return new PemFiles(directory, privateKey, publicKey);
            } catch (GeneralSecurityException | IOException exception) {
                throw new IllegalStateException("Could not create temporary RSA keys for PostgreSQL prod-pos smoke test", exception);
            }
        }

        private static String pem(String type, byte[] encoded) {
            return "-----BEGIN " + type + "-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                    + "\n-----END " + type + "-----\n";
        }
    }
}
