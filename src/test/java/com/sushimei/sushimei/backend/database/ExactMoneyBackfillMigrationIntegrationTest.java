package com.sushimei.sushimei.backend.database;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactMoneyBackfillMigrationIntegrationTest {

    private static final String H2_MIGRATION_LOCATION = "classpath:db/migration/h2";
    private static final String H2_BASELINE_SCRIPT = "db/migration/h2/B1__current_application_schema.sql";
    private static final String V2_SCRIPT = "V2__add_parallel_numeric_money_columns.sql";
    private static final String V3_SCRIPT = "V3__backfill_and_constrain_numeric_money.sql";
    private static final String V4_SCRIPT = "V4__add_whatsapp_inbound_message_idempotency.sql";
    private static final String V5_SCRIPT = "V5__add_structured_order_foundations.sql";
    private static final String V6_SCRIPT = "V6__add_operational_menu_catalog.sql";
    private static final String V7_SCRIPT = "V7__add_configurable_catalog_domain.sql";

    private final List<JdbcConnectionPool> isolatedDataSources = new ArrayList<>();

    @AfterEach
    void closeIsolatedDataSources() {
        isolatedDataSources.forEach(JdbcConnectionPool::dispose);
        isolatedDataSources.clear();
    }

    @Test
    void cleanDatabaseAppliesB1V2AndV3WithoutInsertingApplicationData() {
        JdbcConnectionPool dataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        newFlyway(dataSource).migrate();

        assertSqlMigration(jdbcTemplate, 1, "SQL_BASELINE", "B1__current_application_schema.sql");
        assertSqlMigration(jdbcTemplate, 2, "SQL", V2_SCRIPT);
        assertSqlMigration(jdbcTemplate, 3, "SQL", V3_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 1)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 2)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 3)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 4, "SQL", V4_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 4)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 5, "SQL", V5_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 5)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 6, "SQL", V6_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 6)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 7, "SQL", V7_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 7)).isEqualTo(1);
        assertThat(currentVersion(jdbcTemplate)).isEqualTo("7");
        assertMoneyColumn(jdbcTemplate, "CART_ITEMS", "UNIT_PRICE_AMOUNT", "NO");
        assertMoneyColumn(jdbcTemplate, "ORDERS", "TOTAL_AMOUNT_AMOUNT", "NO");
        assertNamedConstraint(jdbcTemplate, "CART_ITEMS", "CART_ITEMS_UNIT_PRICE_AMOUNT_POSITIVE_CHECK");
        assertNamedConstraint(jdbcTemplate, "CART_ITEMS", "CART_ITEMS_MONEY_REPRESENTATIONS_AGREE_CHECK");
        assertNamedConstraint(jdbcTemplate, "ORDERS", "ORDERS_TOTAL_AMOUNT_AMOUNT_POSITIVE_CHECK");
        assertNamedConstraint(jdbcTemplate, "ORDERS", "ORDERS_MONEY_REPRESENTATIONS_AGREE_CHECK");
        assertNoApplicationData(jdbcTemplate);
    }

    @Test
    void v2LeavesHistoricalNumericColumnsNullableAndNull() {
        JdbcConnectionPool dataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        newFlyway(dataSource, MigrationVersion.fromVersion("2")).migrate();
        Long cartId = insertCart(jdbcTemplate);
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, cart_id) values (?, ?, ?, ?)",
                "Historical Maki", 1, 10.50d, cartId);
        jdbcTemplate.update("insert into public.orders (phone_number, total_amount, status, created_at) values (?, ?, ?, current_timestamp)",
                "525512345678", 0.10d, "PENDING");

        assertThat(currentVersion(jdbcTemplate)).isEqualTo("2");
        assertThat(historyCount(jdbcTemplate, 3)).isZero();
        assertMoneyColumn(jdbcTemplate, "CART_ITEMS", "UNIT_PRICE_AMOUNT", "YES");
        assertMoneyColumn(jdbcTemplate, "ORDERS", "TOTAL_AMOUNT_AMOUNT", "YES");
        assertThat(jdbcTemplate.queryForObject("select unit_price_amount from public.cart_items", Object.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select total_amount_amount from public.orders", Object.class)).isNull();
    }

    @Test
    void explicitBaselineBackfillsExactHistoricalValuesAndRecordsV3Once() {
        JdbcConnectionPool dataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        loadH2BaselineOutsideFlyway(dataSource);
        Long cartId = insertCart(jdbcTemplate);
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, cart_id) values (?, ?, ?, ?)",
                "Legacy Maki", 2, 10.50d, cartId);
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, cart_id) values (?, ?, ?, ?)",
                "Legacy Tea", 1, 0.10d, cartId);
        jdbcTemplate.update("insert into public.orders (phone_number, total_amount, status, created_at) values (?, ?, ?, current_timestamp)",
                "525512345678", 7.25d, "PENDING");
        Flyway flyway = newFlyway(dataSource);

        flyway.baseline();
        flyway.migrate();

        assertThat(historyValue(jdbcTemplate, 1, "type")).isEqualTo("BASELINE");
        assertThat(historyCount(jdbcTemplate, 1)).isEqualTo(1);
        assertThat(historyCountForType(jdbcTemplate, "SQL_BASELINE")).isZero();
        assertSqlMigration(jdbcTemplate, 2, "SQL", V2_SCRIPT);
        assertSqlMigration(jdbcTemplate, 3, "SQL", V3_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 2)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 3)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 4, "SQL", V4_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 4)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 5, "SQL", V5_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 5)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 6, "SQL", V6_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 6)).isEqualTo(1);
        assertSqlMigration(jdbcTemplate, 7, "SQL", V7_SCRIPT);
        assertThat(historyCount(jdbcTemplate, 7)).isEqualTo(1);
        assertThat(currentVersion(jdbcTemplate)).isEqualTo("7");
        assertThat(jdbcTemplate.queryForList("select unit_price from public.cart_items order by id", Double.class))
                .containsExactly(10.50d, 0.10d);
        assertThat(jdbcTemplate.queryForList("select unit_price_amount from public.cart_items order by id", BigDecimal.class))
                .extracting(BigDecimal::toPlainString)
                .containsExactly("10.50", "0.10");
        assertThat(jdbcTemplate.queryForObject("select total_amount from public.orders", Double.class)).isEqualTo(7.25d);
        assertThat(jdbcTemplate.queryForObject("select total_amount_amount from public.orders", BigDecimal.class))
                .isEqualByComparingTo("7.25");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(historyCount(jdbcTemplate, 3)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 4)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 5)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 6)).isEqualTo(1);
        assertThat(historyCount(jdbcTemplate, 7)).isEqualTo(1);
    }

    @Test
    void v3RejectsInvalidHistoricalLegacyDataBeforeAnyBackfill() {
        JdbcConnectionPool dataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        newFlyway(dataSource, MigrationVersion.fromVersion("2")).migrate();
        Long cartId = insertCart(jdbcTemplate);
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, cart_id) values (?, ?, ?, ?)",
                "Valid Historical Maki", 1, 10.50d, cartId);
        jdbcTemplate.update("insert into public.orders (phone_number, total_amount, status, created_at) values (?, ?, ?, current_timestamp)",
                "525512345678", 10.005d, "PENDING");

        assertThatThrownBy(() -> newFlyway(dataSource).migrate()).isInstanceOf(FlywayException.class);

        assertThat(currentVersion(jdbcTemplate)).isEqualTo("2");
        assertThat(jdbcTemplate.queryForObject("select unit_price from public.cart_items", Double.class)).isEqualTo(10.50d);
        assertThat(jdbcTemplate.queryForObject("select unit_price_amount from public.cart_items", Object.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select total_amount from public.orders", Double.class)).isEqualTo(10.005d);
        assertThat(jdbcTemplate.queryForObject("select total_amount_amount from public.orders", Object.class)).isNull();
    }

    @Test
    void v3RejectsExistingMismatchBeforeAnyRowMutation() {
        JdbcConnectionPool dataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        newFlyway(dataSource, MigrationVersion.fromVersion("2")).migrate();
        Long cartId = insertCart(jdbcTemplate);
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, unit_price_amount, cart_id) values (?, ?, ?, ?, ?)",
                "Mismatched Maki", 1, 10.50d, new BigDecimal("10.51"), cartId);
        jdbcTemplate.update("insert into public.orders (phone_number, total_amount, status, created_at) values (?, ?, ?, current_timestamp)",
                "525512345678", 0.10d, "PENDING");

        assertThatThrownBy(() -> newFlyway(dataSource).migrate()).isInstanceOf(FlywayException.class);

        assertThat(currentVersion(jdbcTemplate)).isEqualTo("2");
        assertThat(jdbcTemplate.queryForObject("select unit_price_amount from public.cart_items", BigDecimal.class))
                .isEqualByComparingTo("10.51");
        assertThat(jdbcTemplate.queryForObject("select total_amount from public.orders", Double.class)).isEqualTo(0.10d);
        assertThat(jdbcTemplate.queryForObject("select total_amount_amount from public.orders", Object.class)).isNull();
    }

    @Test
    void v3ConstraintsEnforceExactPairsAndJavaCanonicalBoundaryValues() {
        JdbcConnectionPool dataSource = newIsolatedDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        newFlyway(dataSource).migrate();
        Long cartId = insertCart(jdbcTemplate);

        assertRejected(() -> insertCartItem(jdbcTemplate, "Null Numeric", 10.50d, null, cartId));
        assertRejected(() -> insertCartItem(jdbcTemplate, "Zero Numeric", null, BigDecimal.ZERO, cartId));
        assertRejected(() -> insertCartItem(jdbcTemplate, "Negative Numeric", null, new BigDecimal("-0.01"), cartId));
        assertRejected(() -> insertCartItem(jdbcTemplate, "Mismatched Numeric", 10.50d, new BigDecimal("10.51"), cartId));
        insertCartItem(jdbcTemplate, "Exact Pair", 10.50d, new BigDecimal("10.50"), cartId);
        insertCartItem(jdbcTemplate, "Numeric Only", null, new BigDecimal("0.10"), cartId);

        assertRejected(() -> insertOrder(jdbcTemplate, 10.50d, null));
        assertRejected(() -> insertOrder(jdbcTemplate, null, BigDecimal.ZERO));
        assertRejected(() -> insertOrder(jdbcTemplate, null, new BigDecimal("-0.01")));
        assertRejected(() -> insertOrder(jdbcTemplate, 10.50d, new BigDecimal("10.51")));
        insertOrder(jdbcTemplate, 10.50d, new BigDecimal("10.50"));
        insertOrder(jdbcTemplate, null, new BigDecimal("0.10"));

        double javaCanonicalLegacyAmount = 99999999999999.98d;
        BigDecimal javaCanonicalNumericAmount = new BigDecimal("99999999999999.98");
        insertCartItem(jdbcTemplate, "Java Canonical Pair", javaCanonicalLegacyAmount, javaCanonicalNumericAmount, cartId);
        insertOrder(jdbcTemplate, javaCanonicalLegacyAmount, javaCanonicalNumericAmount);
        assertRejected(() -> insertCartItem(jdbcTemplate, "Java Incompatible Pair", javaCanonicalLegacyAmount,
                new BigDecimal("99999999999999.99"), cartId));
        assertRejected(() -> insertOrder(jdbcTemplate, javaCanonicalLegacyAmount,
                new BigDecimal("99999999999999.99")));
    }

    private JdbcConnectionPool newIsolatedDataSource() {
        JdbcConnectionPool dataSource = JdbcConnectionPool.create(
                "jdbc:h2:mem:phase5a2b2_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                ""
        );
        isolatedDataSources.add(dataSource);
        return dataSource;
    }

    private Flyway newFlyway(DataSource dataSource) {
        return newFlyway(dataSource, null);
    }

    private Flyway newFlyway(DataSource dataSource, MigrationVersion targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(H2_MIGRATION_LOCATION)
                .defaultSchema("PUBLIC")
                .schemas("PUBLIC")
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .cleanDisabled(true)
                .validateMigrationNaming(true);
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        return configuration.load();
    }

    private void loadH2BaselineOutsideFlyway(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(H2_BASELINE_SCRIPT)).execute(dataSource);
    }

    private Long insertCart(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("insert into public.cart (phone_number, status) values (?, ?)", "525512345678", "OPEN");
        return jdbcTemplate.queryForObject("select id from public.cart where phone_number = ?", Long.class, "525512345678");
    }

    private void insertCartItem(JdbcTemplate jdbcTemplate, String dishName, Double legacyAmount, BigDecimal numericAmount, Long cartId) {
        jdbcTemplate.update("insert into public.cart_items (dish_name, quantity, unit_price, unit_price_amount, cart_id) values (?, ?, ?, ?, ?)",
                dishName, 1, legacyAmount, numericAmount, cartId);
    }

    private void insertOrder(JdbcTemplate jdbcTemplate, Double legacyAmount, BigDecimal numericAmount) {
        jdbcTemplate.update("insert into public.orders (phone_number, total_amount, total_amount_amount, status, created_at) values (?, ?, ?, ?, current_timestamp)",
                "525512345678", legacyAmount, numericAmount, "PENDING");
    }

    private void assertSqlMigration(JdbcTemplate jdbcTemplate, int version, String type, String script) {
        assertThat(historyValue(jdbcTemplate, version, "type")).isEqualTo(type);
        assertThat(historyValue(jdbcTemplate, version, "script")).isEqualTo(script);
        assertThat(historySuccess(jdbcTemplate, version)).isTrue();
    }

    private String historyValue(JdbcTemplate jdbcTemplate, int version, String column) {
        return jdbcTemplate.queryForObject("""
                select "%s" from public."flyway_schema_history" where "version" = ?
                """.formatted(column), String.class, Integer.toString(version));
    }

    private int historyCount(JdbcTemplate jdbcTemplate, int version) {
        return jdbcTemplate.queryForObject("""
                select count(*) from public."flyway_schema_history" where "version" = ?
                """, Integer.class, Integer.toString(version));
    }

    private int historyCountForType(JdbcTemplate jdbcTemplate, String type) {
        return jdbcTemplate.queryForObject("""
                select count(*) from public."flyway_schema_history" where "type" = ?
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
                select "success" from public."flyway_schema_history" where "version" = ?
                """, Boolean.class, Integer.toString(version)));
    }

    private void assertMoneyColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName, String nullable) {
        var column = jdbcTemplate.queryForMap("""
                select data_type, numeric_precision, numeric_scale, is_nullable, column_default
                from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, tableName, columnName);
        assertThat(column.get("DATA_TYPE")).isIn("NUMERIC", "DECIMAL");
        assertThat(((Number) column.get("NUMERIC_PRECISION")).intValue()).isEqualTo(19);
        assertThat(((Number) column.get("NUMERIC_SCALE")).intValue()).isEqualTo(2);
        assertThat(column.get("IS_NULLABLE")).isEqualTo(nullable);
        assertThat(column.get("COLUMN_DEFAULT")).isNull();
    }

    private void assertNamedConstraint(JdbcTemplate jdbcTemplate, String tableName, String constraintName) {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = 'PUBLIC' and table_name = ? and constraint_name = ?
                """, Integer.class, tableName, constraintName)).isEqualTo(1);
    }

    private void assertNoApplicationData(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.cart", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.cart_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.conversation_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.carts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.whatsapp_inbound_messages", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.catalog_tags", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_groups", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.menu_selection_rules", Integer.class)).isZero();
    }

    private void assertRejected(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }
}
