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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, FlywayBaselineIntegrationTest.TestInfrastructureConfiguration.class})
class FlywayBaselineIntegrationTest {

    private static final String H2_MIGRATION_LOCATION = "classpath:db/migration/h2";
    private static final String H2_BASELINE_SCRIPT = "db/migration/h2/B1__current_application_schema.sql";
    private static final String V2_SCRIPT = "V2__add_parallel_numeric_money_columns.sql";
    private static final String V3_SCRIPT = "V3__backfill_and_constrain_numeric_money.sql";
    private static final String V4_SCRIPT = "V4__add_whatsapp_inbound_message_idempotency.sql";
    private static final String V5_SCRIPT = "V5__add_structured_order_foundations.sql";
    private static final String V6_SCRIPT = "V6__add_operational_menu_catalog.sql";
    private static final String V7_SCRIPT = "V7__add_configurable_catalog_domain.sql";
    private static final String V8_SCRIPT = "V8__add_temporal_promotions.sql";
    private static final String V9_SCRIPT = "V9__add_application_security.sql";
    private static final String V10_SCRIPT = "V10__add_manual_pos_order_foundations.sql";
    private static final String V11_SCRIPT = "V11__add_authoritative_catalog_rules.sql";

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
    void applicationContextUsesFlywayMigrationsAndHibernateValidation() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertSqlMigration(jdbcTemplate, 1, "SQL_BASELINE", "B1__current_application_schema.sql");
        assertSqlMigration(jdbcTemplate, 2, "SQL", V2_SCRIPT);
        assertSqlMigration(jdbcTemplate, 3, "SQL", V3_SCRIPT);
        assertSqlMigration(jdbcTemplate, 4, "SQL", V4_SCRIPT);
        assertSqlMigration(jdbcTemplate, 5, "SQL", V5_SCRIPT);
        assertSqlMigration(jdbcTemplate, 6, "SQL", V6_SCRIPT);
        assertSqlMigration(jdbcTemplate, 7, "SQL", V7_SCRIPT);
        assertSqlMigration(jdbcTemplate, 8, "SQL", V8_SCRIPT);
        assertSqlMigration(jdbcTemplate, 9, "SQL", V9_SCRIPT);
        assertSqlMigration(jdbcTemplate, 10, "SQL", V10_SCRIPT);
        assertSqlMigration(jdbcTemplate, 11, "SQL", V11_SCRIPT);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("11");
        assertFlywayHistoryTableExistsInPublic(jdbcTemplate);

        assertTableExists(jdbcTemplate, "CART");
        assertTableExists(jdbcTemplate, "CART_ITEMS");
        assertTableExists(jdbcTemplate, "ORDERS");
        assertTableExists(jdbcTemplate, "CONVERSATION_SESSIONS");
        assertTableExists(jdbcTemplate, "CARTS");
        assertTableExists(jdbcTemplate, "WHATSAPP_INBOUND_MESSAGES");
        assertTableExists(jdbcTemplate, "ORDER_LINES");
        assertTableExists(jdbcTemplate, "MENU_ITEMS");
        assertTableExists(jdbcTemplate, "CATALOG_TAGS");
        assertTableExists(jdbcTemplate, "MENU_ITEM_TAGS");
        assertTableExists(jdbcTemplate, "MENU_SELECTION_GROUPS");
        assertTableExists(jdbcTemplate, "MENU_SELECTION_RULES");
        assertTableExists(jdbcTemplate, "PROMOTIONS");
        assertTableExists(jdbcTemplate, "PROMOTION_WEEKDAYS");
        assertTableExists(jdbcTemplate, "PROMOTION_TARGETS");
        assertTableExists(jdbcTemplate, "APP_USERS");
        assertTableExists(jdbcTemplate, "AUTH_SESSIONS");
        assertTableExists(jdbcTemplate, "AUTH_REFRESH_TOKEN_HISTORY");
        assertTableExists(jdbcTemplate, "SECURITY_AUDIT_EVENTS");
        assertTableExists(jdbcTemplate, "ORDER_LINE_SELECTION_SNAPSHOTS");
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
        assertThat(namedConstraintExists(jdbcTemplate, "WHATSAPP_INBOUND_MESSAGES", "WHATSAPP_INBOUND_MESSAGES_PKEY"))
                .isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "WHATSAPP_INBOUND_MESSAGES",
                "WHATSAPP_INBOUND_MESSAGES_PROCESSING_STATUS_CHECK")).isTrue();
        assertThat(identityValue(jdbcTemplate, "CART", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "CART_ITEMS", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "ORDERS", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "CARTS", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "MENU_ITEMS", "ID")).isEqualTo("YES");

        assertConstrainedParallelMoneyColumn(jdbcTemplate, "CART_ITEMS", "UNIT_PRICE_AMOUNT");
        assertConstrainedParallelMoneyColumn(jdbcTemplate, "ORDERS", "TOTAL_AMOUNT_AMOUNT");
        assertColumnPresent(jdbcTemplate, "CART_ITEMS", "UNIT_PRICE");
        assertColumnPresent(jdbcTemplate, "ORDERS", "TOTAL_AMOUNT");
        assertColumnPresent(jdbcTemplate, "ORDERS", "SOURCE_CART_ID");
        assertColumnLength(jdbcTemplate, "ORDERS", "TRANSFER_RECEIPT_PATH", 1024);
        assertOperationalMenuCatalogSchema(jdbcTemplate);
        assertConfigurableCatalogSchema(jdbcTemplate);
        assertTemporalPromotionSchema(jdbcTemplate);
        assertSecuritySchema(jdbcTemplate);
        assertManualPosOrderSchema(jdbcTemplate);
        assertAuthoritativeCatalogRulesSchema(jdbcTemplate);
        assertAuthoritativeCatalogBootstrapData(jdbcTemplate);
    }

    @Test
    void cleanIsolatedDatabaseRecordsAllMigrationsThroughV11AsSuccessfulSqlMigrations() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);

        newFlyway(isolatedDataSource).migrate();

        assertSqlMigration(jdbcTemplate, 1, "SQL_BASELINE", "B1__current_application_schema.sql");
        assertSqlMigration(jdbcTemplate, 2, "SQL", V2_SCRIPT);
        assertSqlMigration(jdbcTemplate, 3, "SQL", V3_SCRIPT);
        assertSqlMigration(jdbcTemplate, 4, "SQL", V4_SCRIPT);
        assertSqlMigration(jdbcTemplate, 5, "SQL", V5_SCRIPT);
        assertSqlMigration(jdbcTemplate, 6, "SQL", V6_SCRIPT);
        assertSqlMigration(jdbcTemplate, 7, "SQL", V7_SCRIPT);
        assertSqlMigration(jdbcTemplate, 8, "SQL", V8_SCRIPT);
        assertSqlMigration(jdbcTemplate, 9, "SQL", V9_SCRIPT);
        assertSqlMigration(jdbcTemplate, 10, "SQL", V10_SCRIPT);
        assertSqlMigration(jdbcTemplate, 11, "SQL", V11_SCRIPT);
        assertThat(currentVersion(jdbcTemplate)).isEqualTo("11");
        assertFlywayHistoryTableExistsInPublic(jdbcTemplate);
        assertConstrainedParallelMoneyColumn(jdbcTemplate, "CART_ITEMS", "UNIT_PRICE_AMOUNT");
        assertConstrainedParallelMoneyColumn(jdbcTemplate, "ORDERS", "TOTAL_AMOUNT_AMOUNT");
        assertStructuredOrderConstraints(jdbcTemplate);
        assertColumnLength(jdbcTemplate, "ORDERS", "TRANSFER_RECEIPT_PATH", 1024);
        assertThat(identityValue(jdbcTemplate, "ORDER_LINES", "ID")).isEqualTo("YES");
        assertThat(identityValue(jdbcTemplate, "MENU_ITEMS", "ID")).isEqualTo("YES");
        assertOperationalMenuCatalogSchema(jdbcTemplate);
        assertConfigurableCatalogSchema(jdbcTemplate);
        assertTemporalPromotionSchema(jdbcTemplate);
        assertSecuritySchema(jdbcTemplate);
        assertManualPosOrderSchema(jdbcTemplate);
        assertAuthoritativeCatalogRulesSchema(jdbcTemplate);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.catalog_bootstrap_rule_sets
                where rule_set_id = 'PHASE_6F1_AUTHORITATIVE_CATALOG_RULES' and applied_at is null
                """, Integer.class)).isEqualTo(1);
        jdbcTemplate.update("""
                insert into public.menu_items (name, category, price_amount, pricing_mode, active, available,
                    standalone_orderable, display_order, created_at, updated_at, version)
                values ('Identity reservation probe', 'Migration test', 1.00, 'BASE_PLUS_ADJUSTMENTS', true, true,
                    true, 0, current_timestamp, current_timestamp, 0)
                """);
        assertThat(jdbcTemplate.queryForObject(
                "select id from public.menu_items where name = 'Identity reservation probe'", Long.class))
                .isEqualTo(122L);
        jdbcTemplate.update("delete from public.menu_items where name = 'Identity reservation probe'");
        assertNoBaselineData(jdbcTemplate);
    }

    @Test
    void v7BackfillsExistingMenuItemsToStandaloneOrderableWithoutLeavingADefault() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);
        Flyway.configure().dataSource(isolatedDataSource).locations(H2_MIGRATION_LOCATION)
                .defaultSchema("PUBLIC").schemas("PUBLIC").baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("1")).cleanDisabled(true)
                .validateMigrationNaming(true).target(MigrationVersion.fromVersion("6")).load().migrate();
        jdbcTemplate.update("""
                insert into public.menu_items (
                    name, category, price_amount, active, available, display_order, created_at, updated_at, version
                ) values ('Legacy menu item', 'Legacy', 10.00, true, true, 0, current_timestamp, current_timestamp, 0)
                """);

        newFlyway(isolatedDataSource).migrate();

        assertSqlMigration(jdbcTemplate, 7, "SQL", V7_SCRIPT);
        assertThat(jdbcTemplate.queryForObject("select standalone_orderable from public.menu_items", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select column_default
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'MENU_ITEMS' and column_name = 'STANDALONE_ORDERABLE'
                """, String.class)).isNull();
    }
    @Test
    void menuCatalogConstraintsRejectInvalidPersistedValues() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);
        newFlyway(isolatedDataSource).migrate();

        assertThatThrownBy(() -> insertMenuItem(jdbcTemplate, " ", "Rollos", "79.00", 0, 0,
                "current_timestamp", "current_timestamp"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMenuItem(jdbcTemplate, "California", " ", "79.00", 0, 0,
                "current_timestamp", "current_timestamp"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMenuItem(jdbcTemplate, "California", "Rollos", "0.00", 0, 0,
                "current_timestamp", "current_timestamp"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMenuItem(jdbcTemplate, "California", "Rollos", "79.00", -1, 0,
                "current_timestamp", "current_timestamp"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMenuItem(jdbcTemplate, "California", "Rollos", "79.00", 0, -1,
                "current_timestamp", "current_timestamp"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMenuItem(jdbcTemplate, "California", "Rollos", "79.00", 0, 0,
                "current_timestamp", "dateadd('SECOND', -1, current_timestamp)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("""
                insert into public.menu_items (name, category, price_amount, pricing_mode, active, available,
                    standalone_orderable, display_order, created_at, updated_at, version)
                values ('Arma tu Charola', 'Charolas/Sushi Box', 0.00, 'SELECTION_SUM', true, true, true, 0,
                    current_timestamp, current_timestamp, 0)
                """);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.menu_items (name, category, price_amount, pricing_mode, active, available,
                    standalone_orderable, display_order, created_at, updated_at, version)
                values ('Charola invÃ¡lida', 'Charolas/Sushi Box', 1.00, 'SELECTION_SUM', true, true, true, 0,
                    current_timestamp, current_timestamp, 0)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
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
    void explicitBaselineOfMatchingSchemaDoesNotExecuteB1AndExecutesLaterMigrationsOnce() {
        JdbcConnectionPool isolatedDataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(isolatedDataSource);
        loadH2BaselineOutsideFlyway(isolatedDataSource);
        jdbcTemplate.update("insert into public.cart (phone_number, status) values (?, ?)", "525512345678", "OPEN");
        Long legacyCartId = jdbcTemplate.queryForObject(
                "select id from public.cart where phone_number = ?", Long.class, "525512345678");
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, cart_id) values (?, ?, ?, ?)",
                "Legacy Maki", 2, 10.50d, legacyCartId);
        jdbcTemplate.update("insert into public.orders (phone_number, total_amount, status, created_at) values (?, ?, ?, current_timestamp)",
                "525512345678", 10.50d, "PENDING");
        int tableCountBeforeBaseline = publicTableCount(jdbcTemplate);
        Flyway isolatedFlyway = newFlyway(isolatedDataSource);

        isolatedFlyway.baseline();
        isolatedFlyway.migrate();

        assertThat(historyValue(jdbcTemplate, 1, "type")).isEqualTo("BASELINE");
        assertThat(historyCount(jdbcTemplate, 1)).isEqualTo(1);
        assertThat(historyCountForType(jdbcTemplate, "SQL_BASELINE")).isZero();
        assertSqlMigration(jdbcTemplate, 2, "SQL", V2_SCRIPT);
        assertSqlMigration(jdbcTemplate, 3, "SQL", V3_SCRIPT);
        assertSqlMigration(jdbcTemplate, 4, "SQL", V4_SCRIPT);
        assertSqlMigration(jdbcTemplate, 5, "SQL", V5_SCRIPT);
        assertSqlMigration(jdbcTemplate, 6, "SQL", V6_SCRIPT);
        assertSqlMigration(jdbcTemplate, 7, "SQL", V7_SCRIPT);
        assertSqlMigration(jdbcTemplate, 8, "SQL", V8_SCRIPT);
        assertSqlMigration(jdbcTemplate, 9, "SQL", V9_SCRIPT);
        assertSqlMigration(jdbcTemplate, 10, "SQL", V10_SCRIPT);
        assertSqlMigration(jdbcTemplate, 11, "SQL", V11_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 2)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 3)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 4)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 5)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 6)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 7)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 8)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 9)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 10)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 11)).isEqualTo(1);
        assertThat(currentVersion(jdbcTemplate)).isEqualTo("11");
        assertThat(publicTableCount(jdbcTemplate)).isEqualTo(tableCountBeforeBaseline + 16);
        assertThat(jdbcTemplate.queryForObject("select dish_name from public.cart_items", String.class)).isEqualTo("Legacy Maki");
        assertThat(jdbcTemplate.queryForObject("select quantity from public.cart_items", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select unit_price from public.cart_items", Double.class)).isEqualTo(10.50d);
        assertThat(jdbcTemplate.queryForObject("select cart_id from public.cart_items", Long.class)).isEqualTo(legacyCartId);
        assertThat(jdbcTemplate.queryForObject("select unit_price_amount from public.cart_items", BigDecimal.class))
                .isEqualByComparingTo("10.50");
        assertThat(jdbcTemplate.queryForObject("select total_amount from public.orders", Double.class)).isEqualTo(10.50d);
        assertThat(jdbcTemplate.queryForObject("select total_amount_amount from public.orders", BigDecimal.class))
                .isEqualByComparingTo("10.50");
        assertThat(jdbcTemplate.queryForObject("select source_cart_id from public.orders", Long.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.catalog_tags", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_groups", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_rules", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotion_weekdays", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotion_targets", Integer.class)).isZero();
        assertThat(isolatedFlyway.migrate().migrationsExecuted).isZero();
        assertThat(historyCount(jdbcTemplate, 8)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 3)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 4)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 5)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 6)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 7)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 9)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 10)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 11)).isEqualTo(1);
        assertTableExists(jdbcTemplate, "CART");
        assertTableExists(jdbcTemplate, "CART_ITEMS");
        assertTableExists(jdbcTemplate, "ORDERS");
        assertTableExists(jdbcTemplate, "CONVERSATION_SESSIONS");
        assertTableExists(jdbcTemplate, "CARTS");
        assertTableExists(jdbcTemplate, "WHATSAPP_INBOUND_MESSAGES");
        assertTableExists(jdbcTemplate, "ORDER_LINES");
        assertTableExists(jdbcTemplate, "MENU_ITEMS");
        assertTableExists(jdbcTemplate, "CATALOG_TAGS");
        assertTableExists(jdbcTemplate, "MENU_ITEM_TAGS");
        assertTableExists(jdbcTemplate, "MENU_SELECTION_GROUPS");
        assertTableExists(jdbcTemplate, "MENU_SELECTION_RULES");
        assertTableExists(jdbcTemplate, "PROMOTIONS");
        assertTableExists(jdbcTemplate, "PROMOTION_WEEKDAYS");
        assertTableExists(jdbcTemplate, "PROMOTION_TARGETS");
        assertTableExists(jdbcTemplate, "APP_USERS");
        assertTableExists(jdbcTemplate, "AUTH_SESSIONS");
        assertTableExists(jdbcTemplate, "AUTH_REFRESH_TOKEN_HISTORY");
        assertTableExists(jdbcTemplate, "SECURITY_AUDIT_EVENTS");
        assertOperationalMenuCatalogSchema(jdbcTemplate);
        assertConfigurableCatalogSchema(jdbcTemplate);
        assertTemporalPromotionSchema(jdbcTemplate);
        assertSecuritySchema(jdbcTemplate);
        assertManualPosOrderSchema(jdbcTemplate);
    }

    private void insertMenuItem(JdbcTemplate jdbcTemplate,
                                String name,
                                String category,
                                String price,
                                int displayOrder,
                                long version,
                                String createdAtExpression,
                                String updatedAtExpression) {
        jdbcTemplate.update("""
                insert into public.menu_items (
                    name, category, price_amount, pricing_mode, active, available, standalone_orderable, display_order, created_at, updated_at, version
                ) values (?, ?, ?, 'BASE_PLUS_ADJUSTMENTS', true, true, true, ?, %s, %s, ?)
                """.formatted(createdAtExpression, updatedAtExpression),
                name, category, new BigDecimal(price), displayOrder, version);
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
                .locations(H2_MIGRATION_LOCATION)
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

    private void assertSqlMigration(JdbcTemplate jdbcTemplate, int version, String type, String script) {
        assertThat(historyValue(jdbcTemplate, version, "type")).isEqualTo(type);
        assertThat(historyValue(jdbcTemplate, version, "script")).isEqualTo(script);
        assertThat(historySuccess(jdbcTemplate, version)).isTrue();
    }

    private String historyValue(JdbcTemplate jdbcTemplate, int version, String column) {
        return jdbcTemplate.queryForObject("""
                select "%s"
                from public."flyway_schema_history"
                where "version" = ?
                """.formatted(column), String.class, Integer.toString(version));
    }

    private int historyCount(JdbcTemplate jdbcTemplate, int version) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from public."flyway_schema_history"
                where "version" = ?
                """, Integer.class, Integer.toString(version));
    }

    private int historyCountForType(JdbcTemplate jdbcTemplate, String type) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from public."flyway_schema_history"
                where "type" = ?
                """, Integer.class, type);
    }

    private String currentVersion(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("""
                select "version"
                from public."flyway_schema_history"
                where "success" = true and "version" is not null
                order by "installed_rank" desc
                fetch first row only
                """, String.class);
    }

    private boolean historySuccess(JdbcTemplate jdbcTemplate, int version) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select "success"
                from public."flyway_schema_history"
                where "version" = ?
                """, Boolean.class, Integer.toString(version)));
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


    private void assertOperationalMenuCatalogSchema(JdbcTemplate jdbcTemplate) {
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS", "MENU_ITEMS_NAME_NOT_BLANK_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS", "MENU_ITEMS_CATEGORY_NOT_BLANK_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS", "MENU_ITEMS_DISPLAY_ORDER_NONNEGATIVE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS", "MENU_ITEMS_VERSION_NONNEGATIVE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS",
                "MENU_ITEMS_UPDATED_AT_NOT_BEFORE_CREATED_AT_CHECK")).isTrue();
        assertColumnLength(jdbcTemplate, "MENU_ITEMS", "NAME", 160);
        assertColumnLength(jdbcTemplate, "MENU_ITEMS", "DESCRIPTION", 1000);
        assertColumnLength(jdbcTemplate, "MENU_ITEMS", "CATEGORY", 120);
        assertThat(jdbcTemplate.queryForObject("""
                select data_type
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'MENU_ITEMS' and column_name = 'PRICE_AMOUNT'
                """, String.class)).isIn("NUMERIC", "DECIMAL");
        assertThat(jdbcTemplate.queryForObject("""
                select numeric_precision
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'MENU_ITEMS' and column_name = 'PRICE_AMOUNT'
                """, Integer.class)).isEqualTo(19);
        assertThat(jdbcTemplate.queryForObject("""
                select numeric_scale
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'MENU_ITEMS' and column_name = 'PRICE_AMOUNT'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'MENU_ITEMS' and column_name = 'PRICE_AMOUNT'
                """, String.class)).isEqualTo("NO");
    }
    private void assertConfigurableCatalogSchema(JdbcTemplate jdbcTemplate) {
        assertColumnPresent(jdbcTemplate, "MENU_ITEMS", "STANDALONE_ORDERABLE");
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'MENU_ITEMS' and column_name = 'STANDALONE_ORDERABLE'
                """, String.class)).isEqualTo("NO");
        assertThat(namedConstraintExists(jdbcTemplate, "CATALOG_TAGS", "CATALOG_TAGS_CODE_KEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEM_TAGS", "MENU_ITEM_TAGS_PKEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_SELECTION_RULES", "MENU_SELECTION_RULES_TARGET_XOR_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_SELECTION_RULES",
                "MENU_SELECTION_RULES_PRICE_PARAMETERS_CHECK")).isTrue();
    }
    private void assertTemporalPromotionSchema(JdbcTemplate jdbcTemplate) {
        assertThat(namedConstraintExists(jdbcTemplate, "PROMOTIONS", "PROMOTIONS_BENEFIT_TYPE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "PROMOTIONS", "PROMOTIONS_BENEFIT_PARAMETERS_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "PROMOTION_WEEKDAYS", "PROMOTION_WEEKDAYS_PKEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "PROMOTION_TARGETS", "PROMOTION_TARGETS_TARGET_XOR_CHECK")).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'PROMOTIONS' and column_name = 'FIXED_UNIT_PRICE_AMOUNT'
                """, String.class)).isIn("NUMERIC", "DECIMAL");
    }
    private void assertStructuredOrderConstraints(JdbcTemplate jdbcTemplate) {
        assertThat(namedConstraintExists(jdbcTemplate, "ORDERS", "ORDERS_SOURCE_CART_ID_KEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDERS", "ORDERS_ORDER_SOURCE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDERS", "ORDERS_FULFILLMENT_TYPE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDERS", "ORDERS_PAYMENT_METHOD_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDERS", "ORDERS_CASH_DENOMINATION_POSITIVE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_ORDER_ID_FKEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_ORDER_LINE_POSITION_KEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_ORDER_SOURCE_CART_ITEM_KEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_LINE_TOTAL_AMOUNT_MATCHES_CHECK")).isTrue();
    }

    private void assertColumnLength(JdbcTemplate jdbcTemplate, String tableName, String columnName, int expectedLength) {
        assertThat(jdbcTemplate.queryForObject("""
                select character_maximum_length
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, Integer.class, tableName, columnName)).isEqualTo(expectedLength);
    }
    private void assertParallelMoneyColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        assertColumnPresent(jdbcTemplate, tableName, columnName);
        assertThat(jdbcTemplate.queryForObject("""
                select data_type
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, String.class, tableName, columnName)).isIn("NUMERIC", "DECIMAL");
        assertThat(jdbcTemplate.queryForObject("""
                select numeric_precision
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, Integer.class, tableName, columnName)).isEqualTo(19);
        assertThat(jdbcTemplate.queryForObject("""
                select numeric_scale
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, Integer.class, tableName, columnName)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, String.class, tableName, columnName)).isEqualTo("YES");
        assertThat(jdbcTemplate.queryForObject("""
                select column_default
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, String.class, tableName, columnName)).isNull();
    }

    private void assertConstrainedParallelMoneyColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        assertColumnPresent(jdbcTemplate, tableName, columnName);
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, String.class, tableName, columnName)).isEqualTo("NO");
    }

    private void assertColumnPresent(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, Integer.class, tableName, columnName)).isEqualTo(1);
    }

    private void assertColumnAbsent(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, Integer.class, tableName, columnName)).isZero();
    }

    private void assertNoBaselineData(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.cart", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.cart_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.conversation_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.carts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.whatsapp_inbound_messages", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_line_selection_snapshots", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.catalog_tags", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_groups", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_rules", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotion_weekdays", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.promotion_targets", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.catalog_bootstrap_rule_sets", Integer.class)).isEqualTo(1);
    }

    private void assertSecuritySchema(JdbcTemplate jdbcTemplate) {
        assertThat(namedConstraintExists(jdbcTemplate, "APP_USERS", "APP_USERS_ROLE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "APP_USERS", "APP_USERS_FAILED_LOGIN_ATTEMPTS_NONNEGATIVE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "AUTH_SESSIONS", "AUTH_SESSIONS_ABSOLUTE_EXPIRES_AFTER_CREATED_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "AUTH_REFRESH_TOKEN_HISTORY", "AUTH_REFRESH_TOKEN_HISTORY_TOKEN_HASH_KEY")).isTrue();
    }

    private void assertManualPosOrderSchema(JdbcTemplate jdbcTemplate) {
        assertColumnPresent(jdbcTemplate, "ORDERS", "CLIENT_REQUEST_ID");
        assertColumnPresent(jdbcTemplate, "ORDERS", "CREATED_BY_USER_ID");
        assertColumnPresent(jdbcTemplate, "ORDERS", "REQUEST_FINGERPRINT");
        assertColumnPresent(jdbcTemplate, "ORDER_LINES", "SOURCE_MENU_ITEM_ID");
        assertColumnPresent(jdbcTemplate, "ORDER_LINES", "CLIENT_LINE_KEY");
        assertColumnPresent(jdbcTemplate, "ORDER_LINES", "LINE_KIND");
        assertColumnPresent(jdbcTemplate, "ORDER_LINES", "SOURCE_PAID_LINE_ID");
        assertTableExists(jdbcTemplate, "ORDER_LINE_SELECTION_SNAPSHOTS");
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_CLIENT_REQUEST_ID_KEY")).isFalse();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDERS", "ORDERS_CLIENT_REQUEST_ID_KEY")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_LINE_KIND_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_MONEY_BY_KIND_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_PROVENANCE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_MANUAL_PRICING_EVIDENCE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "ORDER_LINES", "ORDER_LINES_ORDER_CLIENT_LINE_KEY_KEY")).isTrue();
    }

    private void assertAuthoritativeCatalogBootstrapData(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.catalog_tags", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_groups", Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_rules", Integer.class)).isGreaterThan(0);
    }

    private void assertAuthoritativeCatalogRulesSchema(JdbcTemplate jdbcTemplate) {
        assertColumnPresent(jdbcTemplate, "MENU_ITEMS", "PRICING_MODE");
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS", "MENU_ITEMS_PRICING_MODE_CHECK")).isTrue();
        assertThat(namedConstraintExists(jdbcTemplate, "MENU_ITEMS", "MENU_ITEMS_PRICE_BY_PRICING_MODE_CHECK")).isTrue();
        assertTableExists(jdbcTemplate, "CATALOG_BOOTSTRAP_RULE_SETS");
        assertThat(namedConstraintExists(jdbcTemplate, "CATALOG_BOOTSTRAP_RULE_SETS",
                "CATALOG_BOOTSTRAP_RULE_SETS_RULE_SET_ID_NOT_BLANK_CHECK")).isTrue();
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
