package com.cardovia.merkon.backend.tenant;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.businessday.BusinessDayResponse;
import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.businessday.CashExpenseRequest;
import com.cardovia.merkon.backend.businessday.CashExpenseService;
import com.cardovia.merkon.backend.businessday.CloseBusinessDayRequest;
import com.cardovia.merkon.backend.businessday.OpenBusinessDayRequest;
import com.cardovia.merkon.backend.catalog.CatalogConfigurationException;
import com.cardovia.merkon.backend.catalog.CatalogConfigurationService;
import com.cardovia.merkon.backend.catalog.CatalogTagResponse;
import com.cardovia.merkon.backend.catalog.CreateCatalogTagRequest;
import com.cardovia.merkon.backend.catalog.CreateMenuItemRequest;
import com.cardovia.merkon.backend.catalog.MenuCatalogItemNotFoundException;
import com.cardovia.merkon.backend.catalog.MenuCatalogService;
import com.cardovia.merkon.backend.catalog.MenuItemResponse;
import com.cardovia.merkon.backend.catalog.ReplaceMenuItemTagsRequest;
import com.cardovia.merkon.backend.entity.OrderFulfillmentType;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderPaymentTiming;
import com.cardovia.merkon.backend.order.OrderLifecycleException;
import com.cardovia.merkon.backend.order.OrderLifecycleService;
import com.cardovia.merkon.backend.order.OrderPaymentCollectionRequest;
import com.cardovia.merkon.backend.order.OrderVoidRequest;
import com.cardovia.merkon.backend.orderread.OperationalOrderReadException;
import com.cardovia.merkon.backend.orderread.OperationalOrderReadService;
import com.cardovia.merkon.backend.pos.ManualPosOrderRequest;
import com.cardovia.merkon.backend.pos.ManualPosOrderResponse;
import com.cardovia.merkon.backend.pos.ManualPosOrderService;
import com.cardovia.merkon.backend.promotion.CreatePromotionRequest;
import com.cardovia.merkon.backend.promotion.PromotionBenefitType;
import com.cardovia.merkon.backend.promotion.PromotionException;
import com.cardovia.merkon.backend.promotion.PromotionResponse;
import com.cardovia.merkon.backend.promotion.PromotionService;
import com.cardovia.merkon.backend.promotion.PromotionTargetRequest;
import com.cardovia.merkon.backend.promotion.PromotionTargetType;
import com.cardovia.merkon.backend.promotion.PromotionQuoteLineRequest;
import com.cardovia.merkon.backend.security.ApplicationRole;
import com.cardovia.merkon.backend.security.SecurityTestKeyConfiguration;
import com.cardovia.merkon.backend.security.TestBusinessAuthentication;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hostile A/B identifier tests for every current tenant-owned operational root aggregate. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, OperationalTenantIsolationIntegrationTest.TestInfrastructureConfiguration.class})
class OperationalTenantIsolationIntegrationTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private MenuCatalogService catalog;
    @Autowired private CatalogConfigurationService configuration;
    @Autowired private PromotionService promotions;
    @Autowired private ManualPosOrderService manualOrders;
    @Autowired private OrderLifecycleService lifecycle;
    @Autowired private OperationalOrderReadService orderReads;
    @Autowired private BusinessDayService businessDays;
    @Autowired private CashExpenseService expenses;
    @Autowired private Clock clock;
    @Autowired private MockMvc mockMvc;

    private Business businessA;
    private Business businessB;
    private Long actorA;
    private Long actorB;

    @BeforeEach
    void setUpBusinesses() {
        String suffix = UUID.randomUUID().toString();
        businessA = businesses.saveAndFlush(Business.create("Tenant A " + suffix, clock.instant()));
        businessB = businesses.saveAndFlush(Business.create("Tenant B " + suffix, clock.instant()));
        actorA = actor("tenant-a-" + suffix);
        actorB = actor("tenant-b-" + suffix);
    }

    @Test
    void catalogAndConfigurationRejectCrossTenantIdentifiersAtServiceAndHttpBoundaries() throws Exception {
        MenuItemResponse itemA = item(businessA, "A item");
        MenuItemResponse itemB = item(businessB, "B item");
        CatalogTagResponse tagB = configuration.createTag(businessB.getId(),
                new CreateCatalogTagRequest("B-" + itemB.id(), "B tag", 0));

        assertThat(catalog.list(businessA.getId(), false, false)).extracting(MenuItemResponse::id)
                .contains(itemA.id()).doesNotContain(itemB.id());
        assertThatThrownBy(() -> catalog.get(businessA.getId(), itemB.id()))
                .isInstanceOf(MenuCatalogItemNotFoundException.class);
        assertThatThrownBy(() -> configuration.replaceItemTags(businessA.getId(), itemA.id(),
                new ReplaceMenuItemTagsRequest(itemA.version(), List.of(tagB.id()))))
                .isInstanceOf(CatalogConfigurationException.class);

        mockMvc.perform(get("/api/v1/menu/items").with(TestBusinessAuthentication.authenticated(
                        jdbc(), businessA.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + itemB.id() + ")]").isEmpty());
        mockMvc.perform(get("/api/v1/menu/items/{id}", itemB.id()).with(TestBusinessAuthentication.authenticated(
                        jdbc(), businessA.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void promotionsCannotBeReadOrBoundAcrossBusinesses() throws Exception {
        MenuItemResponse itemA = item(businessA, "A promotion item");
        MenuItemResponse itemB = item(businessB, "B promotion item");
        PromotionResponse promotionB = promotions.create(businessB.getId(), fixedPricePromotion("B promotion", itemB.id()));

        assertThat(promotions.list(businessA.getId(), false)).extracting(PromotionResponse::id)
                .doesNotContain(promotionB.id());
        assertThatThrownBy(() -> promotions.get(businessA.getId(), promotionB.id()))
                .isInstanceOf(PromotionException.class);
        assertThatThrownBy(() -> promotions.create(businessA.getId(), fixedPricePromotion("invalid cross tenant", itemB.id())))
                .isInstanceOf(PromotionException.class);

        mockMvc.perform(get("/api/v1/promotions/{id}", promotionB.id()).with(TestBusinessAuthentication.authenticated(
                        jdbc(), businessA.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isNotFound());
        assertThat(itemA.id()).isNotEqualTo(itemB.id());
    }

    @Test
    void ordersBusinessDaysCashExpensesAndAnalyticsStayTenantLocal() throws Exception {
        MenuItemResponse itemA = item(businessA, "A cash item");
        MenuItemResponse itemB = item(businessB, "B cash item");
        businessDays.open(businessA.getId(), actorA, new OpenBusinessDayRequest(new BigDecimal("50.00")));
        businessDays.open(businessB.getId(), actorB, new OpenBusinessDayRequest(new BigDecimal("50.00")));

        ManualPosOrderResponse orderA = manualOrders.create(businessA.getId(), actorA, cashRequest(itemA.id()));
        ManualPosOrderResponse orderB = manualOrders.create(businessB.getId(), actorB, cashRequest(itemB.id()));
        ManualPosOrderResponse deferredOrderB = manualOrders.create(businessB.getId(), actorB, onDeliveryRequest(itemB.id()));
        assertThat(orderReads.activeOrders(businessA.getId())).extracting(order -> order.id()).contains(orderA.id()).doesNotContain(orderB.id());
        assertThat(orderReads.activeOrders(businessB.getId())).extracting(order -> order.id()).contains(orderB.id()).doesNotContain(orderA.id());
        assertThatThrownBy(() -> orderReads.order(businessA.getId(), orderB.id()))
                .isInstanceOf(OperationalOrderReadException.class);
        assertThatThrownBy(() -> lifecycle.ready(businessA.getId(), orderB.id()))
                .isInstanceOf(OrderLifecycleException.class);
        assertThatThrownBy(() -> lifecycle.voidOrder(businessA.getId(), orderB.id(), actorA,
                new OrderVoidRequest("Cross-tenant attempt")))
                .isInstanceOf(OrderLifecycleException.class);
        assertThatThrownBy(() -> manualOrders.create(businessA.getId(), actorA, cashRequest(itemB.id())))
                .isInstanceOf(MenuCatalogItemNotFoundException.class);
        mockMvc.perform(get("/api/v1/orders/{id}", orderB.id()).with(TestBusinessAuthentication.authenticated(
                        jdbc(), businessA.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/orders/{id}/void", orderB.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cross-tenant attempt\"}")
                        .with(TestBusinessAuthentication.authenticated(jdbc(), businessA.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isNotFound());

        lifecycle.ready(businessA.getId(), orderA.id());
        lifecycle.complete(businessA.getId(), orderA.id());
        lifecycle.ready(businessB.getId(), orderB.id());
        lifecycle.complete(businessB.getId(), orderB.id());
        lifecycle.ready(businessB.getId(), deferredOrderB.id());
        assertThatThrownBy(() -> lifecycle.collectPayment(businessA.getId(), deferredOrderB.id(), actorA,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null)))
                .isInstanceOf(OrderLifecycleException.class);
        mockMvc.perform(put("/api/orders/{id}/collect-payment", deferredOrderB.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CARD\"}")
                        .with(TestBusinessAuthentication.authenticated(jdbc(), businessA.getId(), ApplicationRole.OWNER)))
                .andExpect(status().isNotFound());
        lifecycle.collectPayment(businessB.getId(), deferredOrderB.id(), actorB,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null));
        expenses.create(businessA.getId(), actorA, new CashExpenseRequest(
                UUID.randomUUID(), new BigDecimal("5.00"), "Tenant A expense", null));

        BusinessDayResponse closeA = businessDays.close(businessA.getId(), actorA,
                new CloseBusinessDayRequest(new BigDecimal("55.00")));
        BusinessDayResponse closeB = businessDays.close(businessB.getId(), actorB,
                new CloseBusinessDayRequest(new BigDecimal("70.00")));
        assertThat(closeA.completedSalesAmount()).isEqualByComparingTo("10.00");
        assertThat(closeA.cashExpenseAmount()).isEqualByComparingTo("5.00");
        assertThat(closeA.expectedClosingCashAmount()).isEqualByComparingTo("55.00");
        assertThat(closeB.completedSalesAmount()).isEqualByComparingTo("40.00");
        assertThat(closeB.cashExpenseAmount()).isEqualByComparingTo("0.00");
        assertThat(closeB.expectedClosingCashAmount()).isEqualByComparingTo("70.00");
        assertThat(expenses.listCurrent(businessB.getId())).isEmpty();
        assertThat(orderReads.historicalAnalytics(businessA.getId(), Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS)).completedRevenue()).isEqualByComparingTo("10.00");
    }

    private MenuItemResponse item(Business business, String name) {
        return catalog.create(business.getId(), new CreateMenuItemRequest(name, null, "Test", new BigDecimal(
                business.equals(businessA) ? "10.00" : "20.00"), true, true, 0));
    }

    private Long actor(String username) {
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, '{bcrypt}not-used', 'OWNER', true, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                """, username, username);
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
    }

    private static CreatePromotionRequest fixedPricePromotion(String name, Long targetItemId) {
        return new CreatePromotionRequest(name, true, 10, PromotionBenefitType.FIXED_UNIT_PRICE,
                new BigDecimal("5.00"), null, null, null, null, null, Set.of(1),
                List.of(new PromotionTargetRequest(PromotionTargetType.ITEM, targetItemId)));
    }

    private static ManualPosOrderRequest cashRequest(Long itemId) {
        return new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH,
                null, "Ana", null, List.of(new PromotionQuoteLineRequest("line", itemId, 1, List.of(), List.of())));
    }

    private static ManualPosOrderRequest onDeliveryRequest(Long itemId) {
        return new ManualPosOrderRequest(UUID.randomUUID(), OrderFulfillmentType.DELIVERY, null,
                OrderPaymentTiming.ON_DELIVERY, "Calle B 123", null, null,
                List.of(new PromotionQuoteLineRequest("deferred-line", itemId, 1, List.of(), List.of())), List.of());
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
