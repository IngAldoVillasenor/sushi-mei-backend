package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Commit-level SCRUM-59 coverage on PostgreSQL.  The service under test owns
 * the destructive plan; JDBC below is only fixture construction and direct
 * persistence verification.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, AccountDeletionPostgreSqlIntegrationTest.Infrastructure.class})
class AccountDeletionPostgreSqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final String PASSWORD = "postgres deletion current password 2026";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("merkon_account_deletion")
            .withUsername("merkon_account_deletion")
            .withPassword("merkon_account_deletion_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/postgresql");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas[0]", () -> "public");
    }

    @Autowired private AccountDeletionService deletion;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private AuthSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private Environment environment;

    @BeforeEach
    void passwordEncoderDefaults() {
        reset(passwords);
        when(passwords.matches(eq(PASSWORD), anyString())).thenReturn(true);
        when(passwords.encode(anyString())).thenReturn("{bcrypt}postgres-deleted-password");
    }

    @AfterEach
    void removeRollbackGuard() {
        jdbc.execute("drop table if exists public.business_deletion_rollback_guard");
    }

    @Test
    void successfulAccountDeletionPhysicallyRemovesSessionsRefreshHistoryAndMembershipsOnPostgreSql() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(jdbc.queryForObject("show server_version", String.class)).startsWith("17.");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.dialect"))
                .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");

        Business business = business("postgres-account-business");
        AppUser user = user("postgres-account-delete@example.com");
        BusinessMembership membership = membership(user, business, ApplicationRole.MANAGER);
        AppUser owner = user("postgres-account-alternate-owner@example.com");
        membership(owner, business, ApplicationRole.OWNER);
        AuthSessionService.SessionToken first = openAndRotate(user, business, "postgres-account-first");
        AuthSessionService.SessionToken second = openAndRotate(user, business, "postgres-account-second");

        assertThat(deletion.confirmInApp(user.getId(), PASSWORD)).isEqualTo(AccountDeletionOutcome.COMPLETED);

        AppUser tombstone = users.findById(user.getId()).orElseThrow();
        assertThat(tombstone.getRegistrationState()).isEqualTo(AccountRegistrationState.DELETED);
        assertThat(tombstone.isActive()).isFalse();
        assertThat(tombstone.getEmail()).isNull();
        assertThat(memberships.findById(membership.getId())).isEmpty();
        assertThat(count("select count(*) from public.auth_sessions where user_id = ?", user.getId())).isZero();
        assertThat(count("select count(*) from public.auth_refresh_token_history where session_id in (?, ?)",
                first.session().getId(), second.session().getId())).isZero();
    }

    @Test
    void deletingOneBusinessRemovesOnlyItsSessionsRefreshHistoryMembershipAndRepresentativeGraph() {
        Business target = business("postgres-target-graph");
        Business other = business("postgres-other-graph");
        AppUser actor = user("postgres-graph-owner@example.com");
        BusinessMembership targetMembership = membership(actor, target, ApplicationRole.OWNER);
        BusinessMembership otherMembership = membership(actor, other, ApplicationRole.MANAGER);
        AuthSessionService.SessionToken targetSession = openAndRotate(actor, target, "postgres-target-session");
        AuthSessionService.SessionToken otherSession = openAndRotate(actor, other, "postgres-other-session");
        GraphIds targetGraph = seedGraph(target, actor, "target");
        GraphIds otherGraph = seedGraph(other, actor, "other");

        deletion.deleteBusiness(actor.getId(), target.getId(), PASSWORD);

        assertThat(businesses.findById(target.getId())).isEmpty();
        assertThat(memberships.findById(targetMembership.getId())).isEmpty();
        assertThat(count("select count(*) from public.auth_sessions where id = ?", targetSession.session().getId())).isZero();
        assertThat(count("select count(*) from public.auth_refresh_token_history where session_id = ?", targetSession.session().getId())).isZero();
        assertGraphGone(target.getId(), targetGraph);

        assertThat(businesses.findById(other.getId())).isPresent();
        assertThat(memberships.findById(otherMembership.getId())).isPresent();
        assertThat(users.findById(actor.getId())).isPresent();
        assertThat(count("select count(*) from public.auth_sessions where id = ?", otherSession.session().getId())).isOne();
        assertThat(count("select count(*) from public.auth_refresh_token_history where session_id = ?", otherSession.session().getId())).isOne();
        assertGraphPresent(other.getId(), otherGraph);
    }

    @Test
    void foreignKeyFailureAfterBusinessOwnedRowsStartDeletingRollsBackThePostgreSqlTransaction() {
        Business target = business("postgres-rollback-graph");
        AppUser actor = user("postgres-rollback-owner@example.com");
        BusinessMembership membership = membership(actor, target, ApplicationRole.OWNER);
        AuthSessionService.SessionToken session = openAndRotate(actor, target, "postgres-rollback-session");
        GraphIds graph = seedGraph(target, actor, "rollback");
        jdbc.execute("""
                create table public.business_deletion_rollback_guard (
                    business_id bigint not null references public.businesses(id)
                )
                """);
        jdbc.update("insert into public.business_deletion_rollback_guard (business_id) values (?)", target.getId());

        assertThatThrownBy(() -> deletion.deleteBusiness(actor.getId(), target.getId(), PASSWORD))
                .isInstanceOf(RuntimeException.class);

        assertThat(businesses.findById(target.getId())).isPresent();
        assertThat(memberships.findById(membership.getId())).isPresent();
        assertThat(count("select count(*) from public.auth_sessions where id = ?", session.session().getId())).isOne();
        assertThat(count("select count(*) from public.auth_refresh_token_history where session_id = ?", session.session().getId())).isOne();
        assertGraphPresent(target.getId(), graph);
    }

    private Business business(String name) {
        return businesses.saveAndFlush(Business.create(name, NOW));
    }

    private AppUser user(String email) {
        AppUser user = AppUser.create(email, "PostgreSQL user", "stored-" + UUID.randomUUID(), ApplicationRole.OWNER, NOW);
        user.setNormalizedEmail(email, AccountRegistrationState.ACTIVE, NOW);
        return users.saveAndFlush(user);
    }

    private BusinessMembership membership(AppUser user, Business business, ApplicationRole role) {
        return memberships.saveAndFlush(BusinessMembership.create(user, business, role, NOW));
    }

    private AuthSessionService.SessionToken openAndRotate(AppUser user, Business business, String device) {
        AuthSessionService.SessionToken opened = sessions.open(user.getId(), user.getPasswordHash(), device, null, null,
                business.getId(), "198.51.100.70");
        sessions.rotate(opened.rawRefreshToken(), device, "198.51.100.70");
        return opened;
    }

    private GraphIds seedGraph(Business business, AppUser user, String label) {
        Long menuItemId = insertAndId("""
                insert into public.menu_items (business_id, name, category, price_amount, active, available, display_order,
                    created_at, updated_at, version, standalone_orderable, pricing_mode)
                values (?, ?, 'TEST', 10.00, true, true, 0, current_timestamp, current_timestamp, 0, true,
                    'BASE_PLUS_ADJUSTMENTS') returning id
                """, business.getId(), "Menu " + label);
        Long tagId = insertAndId("""
                insert into public.catalog_tags (business_id, code, name, active, display_order, created_at, updated_at, version)
                values (?, ?, ?, true, 0, current_timestamp, current_timestamp, 0) returning id
                """, business.getId(), "TAG_" + label.toUpperCase(), "Tag " + label);
        jdbc.update("insert into public.menu_item_tags (menu_item_id, tag_id) values (?, ?)", menuItemId, tagId);
        Long groupId = insertAndId("""
                insert into public.menu_selection_groups (parent_menu_item_id, name, min_selections, max_selections,
                    allow_duplicates, display_order, active, created_at, updated_at, version)
                values (?, 'Grupo', 0, 1, false, 0, true, current_timestamp, current_timestamp, 0) returning id
                """, menuItemId);
        jdbc.update("""
                insert into public.menu_selection_rules (selection_group_id, target_tag_id, pricing_policy, priority, active,
                    created_at, updated_at, version)
                values (?, ?, 'INCLUDED', 0, true, current_timestamp, current_timestamp, 0)
                """, groupId, tagId);
        jdbc.update("""
                insert into public.menu_item_default_components (menu_item_id, component_code, display_name,
                    included_by_default, removable, display_order, active)
                values (?, 'COMPONENT', 'Component', true, true, 0, true)
                """, menuItemId);
        Long promotionId = insertAndId("""
                insert into public.promotions (business_id, name, active, priority, benefit_type, fixed_unit_price_amount,
                    created_at, updated_at, version)
                values (?, ?, true, 0, 'FIXED_UNIT_PRICE', 5.00, current_timestamp, current_timestamp, 0) returning id
                """, business.getId(), "Promotion " + label);
        jdbc.update("insert into public.promotion_targets (promotion_id, target_menu_item_id) values (?, ?)", promotionId, menuItemId);
        jdbc.update("insert into public.promotion_weekdays (promotion_id, iso_day_of_week) values (?, 1)", promotionId);
        Long cartId = insertAndId("""
                insert into public.cart (business_id, phone_number, status) values (?, ?, 'OPEN') returning id
                """, business.getId(), "555" + label);
        jdbc.update("""
                insert into public.cart_items (cart_id, dish_name, quantity, unit_price, unit_price_amount)
                values (?, 'Cart item', 1, 10.00, 10.00)
                """, cartId);
        Long orderId = insertAndId("""
                insert into public.orders (business_id, total_amount, total_amount_amount, created_at, phone_number, status,
                    order_source, client_request_id, created_by_user_id, request_fingerprint, payment_timing,
                    transfer_receipt_path)
                values (?, 10.00, 10.00, current_timestamp, ?, 'PENDING', 'COUNTER', ?, ?, ?, 'IMMEDIATE', ?)
                returning id
                """, business.getId(), "556" + label, UUID.randomUUID(), user.getId(), "a".repeat(64),
                "receipts/" + label + "-order.jpg");
        Long lineId = insertAndId("""
                insert into public.order_lines (order_id, line_position, dish_name, quantity, unit_price_amount,
                    line_total_amount, line_kind, parent_order_source, external_historical)
                values (?, 1, 'Open sale', 1, 10.00, 10.00, 'OPEN_SALE', 'COUNTER', false) returning id
                """, orderId);
        jdbc.update("""
                insert into public.order_line_component_omissions (order_line_id, source_component_id, component_code,
                    component_name, component_display_order)
                values (?, 1, 'OMITTED', 'Omitted', 0)
                """, lineId);
        Long snapshotId = insertAndId("""
                insert into public.order_line_selection_snapshots (order_line_id, group_id, group_name, selection_position,
                    selected_menu_item_id, selected_item_name, quantity, catalog_unit_price, price_adjustment_amount)
                values (?, ?, 'Snapshot group', 1, ?, 'Snapshot item', 1, 10.00, 0.00) returning id
                """, lineId, groupId, menuItemId);
        jdbc.update("""
                insert into public.order_line_selection_component_omissions (selection_snapshot_id, source_component_id,
                    component_code, component_name, component_display_order)
                values (?, 1, 'SNAPSHOT_OMITTED', 'Snapshot omitted', 0)
                """, snapshotId);
        jdbc.update("""
                insert into public.vendis_order_snapshots (order_id, final_total_source, is_revocate)
                values (?, 10.0000, 0)
                """, orderId);
        jdbc.update("""
                insert into public.vendis_payment_snapshots (order_id, position, amount)
                values (?, 1, 10.0000)
                """, orderId);
        Long businessDayId = insertAndId("""
                insert into public.business_days (business_id, business_date, status, opening_cash_amount, opened_at,
                    opened_by_user_id, open_guard, version)
                values (?, current_date, 'OPEN', 10.00, current_timestamp, ?, 1, 0) returning id
                """, business.getId(), user.getId());
        jdbc.update("""
                insert into public.business_day_closures (business_day_id, close_number, closed_at, closed_by_user_id,
                    opening_cash_amount, completed_sales_amount, cash_sales_amount, transfer_sales_amount,
                    card_sales_amount, unclassified_sales_amount, completed_order_count, voided_order_count,
                    expected_closing_cash_amount, actual_closing_cash_amount, cash_difference_amount,
                    cash_expense_amount, cash_expense_count)
                values (?, 1, current_timestamp, ?, 10.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0, 0, 10.00, 10.00, 0.00, 0.00, 0)
                """, businessDayId, user.getId());
        jdbc.update("""
                insert into public.business_day_cash_expenses (business_id, business_day_id, client_request_id,
                    request_fingerprint, amount, description, created_at, created_by_user_id)
                values (?, ?, ?, ?, 1.00, 'Expense', current_timestamp, ?)
                """, business.getId(), businessDayId, UUID.randomUUID(), "b".repeat(64), user.getId());
        jdbc.update("insert into public.business_day_operation_locks (business_id) values (?)", business.getId());
        jdbc.update("""
                insert into public.conversation_sessions (business_id, phone_number, created_at, last_activity_at, state,
                    transfer_receipt_path, updated_at, version)
                values (?, ?, current_timestamp, current_timestamp, 'ORDERING', ?, current_timestamp, 0)
                """, business.getId(), "557" + label, "receipts/" + label + "-conversation.jpg");
        jdbc.update("""
                insert into public.whatsapp_inbound_messages (business_id, message_id, phone_number, message_type,
                    processing_status, received_at)
                values (?, ?, ?, 'TEXT', 'PROCESSING', current_timestamp)
                """, business.getId(), "message-" + label, "558" + label);
        return new GraphIds(orderId, menuItemId, tagId, promotionId, businessDayId, cartId, "557" + label, "message-" + label);
    }

    private void assertGraphGone(Long businessId, GraphIds graph) {
        for (String sql : graphCountQueries(businessId, graph)) {
            assertThat(count(sql)).isZero();
        }
    }

    private void assertGraphPresent(Long businessId, GraphIds graph) {
        for (String sql : graphCountQueries(businessId, graph)) {
            assertThat(count(sql)).isPositive();
        }
    }

    private java.util.List<String> graphCountQueries(Long businessId, GraphIds graph) {
        return java.util.List.of(
                "select count(*) from public.orders where business_id = " + businessId,
                "select count(*) from public.order_lines where order_id = " + graph.orderId(),
                "select count(*) from public.order_line_component_omissions o join public.order_lines l on l.id=o.order_line_id where l.order_id = " + graph.orderId(),
                "select count(*) from public.order_line_selection_snapshots s join public.order_lines l on l.id=s.order_line_id where l.order_id = " + graph.orderId(),
                "select count(*) from public.order_line_selection_component_omissions o join public.order_line_selection_snapshots s on s.id=o.selection_snapshot_id join public.order_lines l on l.id=s.order_line_id where l.order_id = " + graph.orderId(),
                "select count(*) from public.vendis_order_snapshots where order_id = " + graph.orderId(),
                "select count(*) from public.vendis_payment_snapshots where order_id = " + graph.orderId(),
                "select count(*) from public.business_days where business_id = " + businessId,
                "select count(*) from public.business_day_closures where business_day_id = " + graph.businessDayId(),
                "select count(*) from public.business_day_cash_expenses where business_id = " + businessId,
                "select count(*) from public.business_day_operation_locks where business_id = " + businessId,
                "select count(*) from public.cart where business_id = " + businessId,
                "select count(*) from public.cart_items where cart_id = " + graph.cartId(),
                "select count(*) from public.conversation_sessions where business_id = " + businessId,
                "select count(*) from public.whatsapp_inbound_messages where business_id = " + businessId,
                "select count(*) from public.promotions where business_id = " + businessId,
                "select count(*) from public.promotion_targets where promotion_id = " + graph.promotionId(),
                "select count(*) from public.promotion_weekdays where promotion_id = " + graph.promotionId(),
                "select count(*) from public.menu_items where business_id = " + businessId,
                "select count(*) from public.menu_item_tags where menu_item_id = " + graph.menuItemId(),
                "select count(*) from public.menu_selection_groups where parent_menu_item_id = " + graph.menuItemId(),
                "select count(*) from public.menu_selection_rules r join public.menu_selection_groups g on g.id=r.selection_group_id where g.parent_menu_item_id = " + graph.menuItemId(),
                "select count(*) from public.menu_item_default_components where menu_item_id = " + graph.menuItemId(),
                "select count(*) from public.catalog_tags where business_id = " + businessId
        );
    }

    private Long insertAndId(String sql, Object... values) {
        return jdbc.queryForObject(sql, Long.class, values);
    }

    private int count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Integer.class, values);
    }

    private record GraphIds(Long orderId, Long menuItemId, Long tagId, Long promotionId, Long businessDayId,
                            Long cartId, String conversationPhone, String whatsappMessageId) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean("postgresDeletionPasswordEncoder") @Primary PasswordEncoder passwordEncoder() {
            return org.mockito.Mockito.mock(PasswordEncoder.class);
        }
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
