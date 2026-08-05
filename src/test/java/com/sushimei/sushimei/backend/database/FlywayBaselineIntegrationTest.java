package com.sushimei.sushimei.backend.database;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import(FlywayBaselineIntegrationTest.TestInfrastructureConfiguration.class)
class FlywayBaselineIntegrationTest {

    private static final String H2_BASELINE_LOCATION = "classpath:db/migration/h2";
    private static final String H2_BASELINE_SCRIPT = "db/migration/h2/B1__current_application_schema.sql";

    private final List<JdbcConnectionPool> isolatedDataSources = new ArrayList<>();

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @AfterEach
    void closeIsolatedDataSources() {
        isolatedDataSources.forEach(JdbcConnectionPool::dispose);
        isolatedDataSources.clear();
    }

    @Test
    void applicationContextUsesFlywayBaselineAndHibernateValidation() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(historyValue(jdbcTemplate, "version")).isEqualTo("1");
        assertThat(historyValue(jdbcTemplate, "type")).isEqualTo("SQL_BASELINE");
        assertThat(historyValue(jdbcTemplate, "script")).isEqualTo("B1__current_application_schema.sql");
        assertThat(historySuccess(jdbcTemplate)).isTrue();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
        assertFlywayHistoryTableExistsInPublic(jdbcTemplate);

        assertTableExists(jdbcTemplate, "CART");
        assertTableExists(jdbcTemplate, "CART_ITEMS");
        assertTableExists(jdbcTemplate, "ORDERS");
        assertTableExists(jdbcTemplate, "CONVERSATION_SESSIONS");
        assertTableExists(jdbcTemplate, "CARTS");
        assertTableAbsent(jdbcTemplate, "ORDER_LINE_RECORD");
        assertTableAbsent(jdbcTemplate, "ORDER_LINE_RECORDS");
        assertTableAbsent(jdbcTemplate, "HIBERNATE_SEQUENCE");

        assertThat(constraintCount(jdbcTemplate, "CART_ITEMS", "FOREIGN KEY")).isEqualTo(1);
        assertThat(constraintCount(jdbcTemplate, "CONVERSATION_SESSIONS", "PRIMARY KEY")).isEqualTo(1);
        assertThat(namedConstraintExists(jdbcTemplate, "CONVERSATION_SESSIONS", "CONVERSATION_SESSIONS_PKEY"))
                .isTrue();
        assertThat(keyColumnCount(jdbcTemplate, "CONVERSATION_SESSIONS", "CONVERSATION_SESSIONS_PKEY", "PHONE_NUMBER"))
                .isEqualTo(1);
        assertThat(namedConstraintExists(jdbcTemplate, "CONVERSATION_SESSIONS", "CONVERSATION_SESSIONS_STATE_CHECK"))
                .isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "CONVERSATION_SESSIONS", "CONVERSATION_SESSIONS_PAYMENT_METHOD_CHECK"))
                .isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "CONVERSATION_SESSIONS", "CONVERSATION_SESSIONS_FULFILLMENT_TYPE_CHECK"))
                .isTrue();
        assertThat(identityValue(jdbcTemplate, "CART", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "CART_ITEMS", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "ORDERS", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "CARTS", "ID")).isEqualTo("YES");

        assertColumnAbsent(jdbcTemplate, "CART_ITEMS", "UNIT_PRICE_AMOUNT");
        assertColumnAbsent(jdbcTemplate, "ORDERS", "TOTAL_AMOUNT_AMOUNT");
        assertColumnAbsent(jdbcTemplate, "ORDERS", "SOURCE_CART_ID");
    }

    @Test
    void cleanIsolatedDatabaseRecordsB1AsSuccessfulSqlBaseline() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);

        newFlyway(isolatedDataSource).migrate();

        assertThat(historyValue(jdbcTemplate, "version")).isEqualTo("1");
        assertThat(historyValue(jdbcTemplate, "type")).isEqualTo("SQL_BASELINE");
        assertThat(historyValue(jdbcTemplate, "script")).isEqualTo("B1__current_application_schema.sql");
        assertThat(historySuccess(jdbcTemplate)).isTrue();
        assertFlywayHistoryTableExistsInPublic(jdbcTemplate);
        assertNoBaselineData(jdbcTemplate);
    }

    @Test
    void nonEmptyUnbaselinedSchemaFailsInsteadOfBeingSilentlyBaselined() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);
        jdbcTemplate.execute("create table public.unrelated_preexisting_table (id bigint primary key)");

        assertThatThrownBy(() -> newFlyway(isolatedDataSource).migrate())
                .isInstanceOf(FlywayException.class);

        assertTableAbsent(jdbcTemplate, "FLYWAY_SCHEMA_HISTORY");
    }

    @Test
    void explicitBaselineOfMatchingSchemaRecordsBaselineAndDoesNotExecuteB1Again() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);
        loadH2BaselineOutsideFlyway(isolatedDataSource);
        int tableCountBeforeBaseline = publicTableCount(jdbcTemplate);
        Flyway flyway = newFlyway(isolatedDataSource);

        flyway.baseline();
        flyway.migrate();

        assertThat(historyValue(jdbcTemplate, "version")).isEqualTo("1");
        assertThat(historyValue(jdbcTemplate, "type")).isEqualTo("BASELINE");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from public."flyway_schema_history"
                where "type" = 'SQL_BASELINE'
                """, Integer.class)).isZero();
        assertThat(publicTableCount(jdbcTemplate)).isEqualTo(tableCountBeforeBaseline);
        assertTableExists(jdbcTemplate, "CART");
        assertTableExists(jdbcTemplate, "CART_ITEMS");
        assertTableExists(jdbcTemplate, "ORDERS");
        assertTableExists(jdbcTemplate, "CONVERSATION_SESSIONS");
        assertTableExists(jdbcTemplate, "CARTS");
    }

    private JdbcConnectionPool newIsolatedDataSource() {
        String databaseName = "flyway_" + UUID.randomUUID().toString().replace("-", "");
        JdbcConnectionPool dataSource = JdbcConnectionPool.create(
                "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                "");
        isolatedDataSources.add(dataSource);
        return dataSource;
    }

    private Flyway newFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(H2_BASELINE_LOCATION)
                .defaultSchema("PUBLIC")
                .schemas("PUBLIC")
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load();
    }

    private void loadH2BaselineOutsideFlyway(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(H2_BASELINE_SCRIPT));
        populator.execute(dataSource);
    }

    private String historyValue(JdbcTemplate jdbcTemplate, String column) {
        return jdbcTemplate.queryForObject("""
                select "%s"
                from public."flyway_schema_history"
                where "version" = '1'
                """.formatted(column), String.class);
    }

    private boolean historySuccess(JdbcTemplate jdbcTemplate) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select "success"
                from public."flyway_schema_history"
                where "version" = '1'
                """, Boolean.class));
    }

    private void assertFlywayHistoryTableExistsInPublic(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'PUBLIC' and table_name = 'flyway_schema_history'
                """, Integer.class)).isEqualTo(1);
    }

    private void assertTableExists(JdbcTemplate jdbcTemplate, String tableName) {
        assertThat(tableCount(jdbcTemplate, tableName)).isEqualTo(1);
    }

    private void assertTableAbsent(JdbcTemplate jdbcTemplate, String tableName) {
        assertThat(tableCount(jdbcTemplate, tableName)).isZero();
    }

    private int tableCount(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'PUBLIC' and table_name = ?
                """, Integer.class, tableName);
    }

    private int publicTableCount(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'PUBLIC' and table_type = 'BASE TABLE' and lower(table_name) <> 'flyway_schema_history'
                """, Integer.class);
    }

    private int constraintCount(JdbcTemplate jdbcTemplate, String tableName, String constraintType) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = 'PUBLIC'
                  and table_name = ?
                  and constraint_type = ?
                """, Integer.class, tableName, constraintType);
    }

    private boolean namedConstraintExists(JdbcTemplate jdbcTemplate, String tableName, String constraintName) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = 'PUBLIC'
                  and table_name = ?
                  and constraint_name = ?
                """, Integer.class, tableName, constraintName) == 1;
    }

    private int keyColumnCount(JdbcTemplate jdbcTemplate, String tableName, String constraintName, String columnName) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.key_column_usage
                where constraint_schema = 'PUBLIC'
                  and table_name = ?
                  and constraint_name = ?
                  and column_name = ?
                """, Integer.class, tableName, constraintName, columnName);
    }

    private String identityValue(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                select is_identity
                from information_schema.columns
                where table_schema = 'PUBLIC'
                  and table_name = ?
                  and column_name = ?
                """, String.class, tableName, columnName);
    }

    private void assertColumnAbsent(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'PUBLIC'
                  and table_name = ?
                  and column_name = ?
                """, Integer.class, tableName, columnName)).isZero();
    }

    private void assertNoBaselineData(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.cart", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.cart_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.conversation_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.carts", Integer.class)).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
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
