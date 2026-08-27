package com.sushimei.sushimei.backend.businessday;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.entity.BusinessDay;
import com.sushimei.sushimei.backend.entity.BusinessDayClosure;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.repository.BusinessDayClosureRepository;
import com.sushimei.sushimei.backend.repository.BusinessDayRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        BusinessDayServiceIntegrationTest.TestInfrastructureConfiguration.class})
class BusinessDayServiceIntegrationTest {

    private static final Instant BUSINESS_DAY_INSTANT = Instant.parse("2026-08-12T06:30:00Z");

    @Autowired
    private BusinessDayService businessDayService;

    @Autowired
    private BusinessDayRepository businessDayRepository;

    @Autowired
    private BusinessDayClosureRepository businessDayClosureRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestClock testClock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long userId;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.business_day_closures");
        jdbcTemplate.update("delete from public.business_days");
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.vendis_payment_snapshots");
        jdbcTemplate.update("delete from public.vendis_order_snapshots");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.app_users");
        testClock.set(BUSINESS_DAY_INSTANT);
        userId = insertUser("business-day-owner");
    }

    @Test
    void opensWithValidOrZeroCashAndExposesCurrentState() {
        BusinessDayResponse opened = businessDayService.open(userId,
                new OpenBusinessDayRequest(new BigDecimal("0.00")));

        assertThat(opened.status()).isEqualTo(BusinessDayStatus.OPEN);
        assertThat(opened.businessDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(opened.openingCashAmount()).isEqualByComparingTo("0.00");
        assertThat(opened.openedAt()).isEqualTo(BUSINESS_DAY_INSTANT);
        assertThat(opened.closureId()).isNull();
        assertThat(opened.closureNumber()).isNull();
        assertThat(businessDayService.current()).contains(opened);
        assertThat(businessDayService.hasOpenBusinessDay()).isTrue();
    }

    @Test
    void rejectsNegativeAndDuplicateOpening() {
        assertError(() -> businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("-0.01"))),
                BusinessDayError.BUSINESS_DAY_INVALID);

        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("1000.00")));
        assertError(() -> businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("1000.00"))),
                BusinessDayError.BUSINESS_DAY_ALREADY_OPEN);
    }

    @Test
    void closingSnapshotsCompletedSalesByPaymentMethodAndIgnoresCashTender() {
        order("COMPLETED", OrderPaymentMethod.CASH, "350.00", LocalDateTime.of(2026, 8, 12, 6, 5));
        order("COMPLETED", OrderPaymentMethod.TRANSFER, "100.00", LocalDateTime.of(2026, 8, 12, 8, 0));
        order("COMPLETED", OrderPaymentMethod.CARD, "50.00", LocalDateTime.of(2026, 8, 12, 9, 0));
        order("COMPLETED", null, "25.00", LocalDateTime.of(2026, 8, 12, 10, 0));
        order("VOIDED", OrderPaymentMethod.CASH, "999.00", LocalDateTime.of(2026, 8, 12, 11, 0));
        order("COMPLETED", OrderPaymentMethod.CASH, "10.00", LocalDateTime.of(2026, 8, 13, 6, 0));

        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("1000.00")));
        BusinessDayResponse closed = businessDayService.close(userId,
                new CloseBusinessDayRequest(new BigDecimal("1400.00")));

        assertThat(closed.status()).isEqualTo(BusinessDayStatus.CLOSED);
        assertThat(closed.completedSalesAmount()).isEqualByComparingTo("525.00");
        assertThat(closed.cashSalesAmount()).isEqualByComparingTo("350.00");
        assertThat(closed.transferSalesAmount()).isEqualByComparingTo("100.00");
        assertThat(closed.cardSalesAmount()).isEqualByComparingTo("50.00");
        assertThat(closed.unclassifiedSalesAmount()).isEqualByComparingTo("25.00");
        assertThat(closed.completedOrderCount()).isEqualTo(4);
        assertThat(closed.voidedOrderCount()).isEqualTo(1);
        assertThat(closed.expectedClosingCashAmount()).isEqualByComparingTo("1350.00");
        assertThat(closed.actualClosingCashAmount()).isEqualByComparingTo("1400.00");
        assertThat(closed.cashDifferenceAmount()).isEqualByComparingTo("50.00");
        assertThat(closed.closureId()).isNotNull();
        assertThat(closed.closureNumber()).isEqualTo(1);
        BusinessDayResponse current = businessDayService.current().orElseThrow();
        assertThat(current.closureId()).isEqualTo(closed.closureId());
        assertThat(current.closureNumber()).isEqualTo(1);
        assertThat(businessDayRepository.findById(closed.businessDayId()).orElseThrow().getCashSalesAmount())
                .isEqualByComparingTo("350.00");
        assertThat(businessDayService.hasOpenBusinessDay()).isFalse();
        assertError(() -> businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("1400.00"))),
                BusinessDayError.BUSINESS_DAY_NOT_OPEN);
    }

    @Test
    void capturesExactAndShortCashDifferencesWithoutRejectingTheClose() {
        order("COMPLETED", OrderPaymentMethod.CASH, "100.00", LocalDateTime.of(2026, 8, 12, 7, 0));
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("10.00")));
        BusinessDayResponse exact = businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("110.00")));
        assertThat(exact.cashDifferenceAmount()).isEqualByComparingTo("0.00");

        clean();
        order("COMPLETED", OrderPaymentMethod.CASH, "100.00", LocalDateTime.of(2026, 8, 12, 7, 0));
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("10.00")));
        BusinessDayResponse shortCash = businessDayService.close(userId,
                new CloseBusinessDayRequest(new BigDecimal("100.00")));
        assertThat(shortCash.cashDifferenceAmount()).isEqualByComparingTo("-10.00");
    }

    @Test
    void rejectsMissingOpenDayAndNegativeClosingCash() {
        assertError(() -> businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("0.00"))),
                BusinessDayError.BUSINESS_DAY_NOT_OPEN);
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("0.00")));
        assertError(() -> businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("-0.01"))),
                BusinessDayError.BUSINESS_DAY_INVALID);
        assertThat(businessDayService.hasOpenBusinessDay()).isTrue();
    }

    @Test
    void refusesCloseUntilEveryOrderForTheBusinessDateIsTerminal() {
        OrderRecord unresolved = order("PREPARING", OrderPaymentMethod.CASH, "79.00",
                LocalDateTime.of(2026, 8, 12, 7, 0));
        BusinessDayResponse opened = businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("10.00")));

        assertError(() -> businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("89.00"))),
                BusinessDayError.BUSINESS_DAY_HAS_ACTIVE_ORDERS);
        BusinessDay stillOpen = businessDayRepository.findById(opened.businessDayId()).orElseThrow();
        assertThat(stillOpen.getStatus()).isEqualTo(BusinessDayStatus.OPEN);
        assertThat(stillOpen.getCompletedSalesAmount()).isNull();
        assertThat(stillOpen.getExpectedClosingCashAmount()).isNull();
        assertThat(stillOpen.getClosedAt()).isNull();

        unresolved.setStatus(OrderLifecycleStatus.COMPLETED.persistedValue());
        orderRepository.saveAndFlush(unresolved);

        BusinessDayResponse closed = businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("89.00")));
        assertThat(closed.status()).isEqualTo(BusinessDayStatus.CLOSED);
        assertThat(closed.completedOrderCount()).isEqualTo(1);
        assertThat(closed.cashSalesAmount()).isEqualByComparingTo("79.00");
        assertThat(closed.expectedClosingCashAmount()).isEqualByComparingTo("89.00");
        assertThat(businessDayService.current()).contains(closed);
        assertThat(businessDayService.hasOpenBusinessDay()).isFalse();
    }

    @Test
    void usesMexicoCityBusinessDateInsteadOfUtcCalendarDate() {
        testClock.set(Instant.parse("2026-08-12T05:59:00Z"));
        BusinessDayResponse opened = businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("0.00")));

        assertThat(opened.businessDate()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    void distinguishesMissingOpenAndClosedDaysForPhysicalOrderCreation() {
        assertThat(businessDayService.currentBusinessDayStatus()).isEmpty();
        businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.ANDROID_MANUAL, BUSINESS_DAY_INSTANT);

        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("0.00")));
        assertThat(businessDayService.currentBusinessDayStatus()).contains(BusinessDayStatus.OPEN);
        businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.COUNTER, BUSINESS_DAY_INSTANT);

        businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("0.00")));
        assertThat(businessDayService.currentBusinessDayStatus()).contains(BusinessDayStatus.CLOSED);
        assertError(() -> businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.ANDROID_MANUAL,
                BUSINESS_DAY_INSTANT), BusinessDayError.BUSINESS_DAY_CLOSED);
        assertError(() -> businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.COUNTER,
                BUSINESS_DAY_INSTANT), BusinessDayError.BUSINESS_DAY_CLOSED);

        businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.VENDIS_IMPORT, BUSINESS_DAY_INSTANT);
        businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.WHATSAPP_AI, BUSINESS_DAY_INSTANT);
    }

    @Test
    void reopensTodaysClosedDayWithoutChangingItsFirstCloseEvidenceOrOpeningCash() {
        order("COMPLETED", OrderPaymentMethod.CASH, "813.00", LocalDateTime.of(2026, 8, 12, 7, 0));
        BusinessDayResponse opened = businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("1500.00")));
        BusinessDayResponse firstClose = businessDayService.close(userId,
                new CloseBusinessDayRequest(new BigDecimal("2313.00")));
        assertThat(firstClose.closureId()).isNotNull();
        assertThat(firstClose.closureNumber()).isEqualTo(1);
        assertThat(businessDayService.current().orElseThrow().closureId()).isEqualTo(firstClose.closureId());

        List<BusinessDayClosure> firstClosures = businessDayClosureRepository
                .findByBusinessDayIdOrderByCloseNumberAsc(opened.businessDayId());
        assertThat(firstClosures).hasSize(1);
        BusinessDayClosure firstClosure = firstClosures.get(0);
        assertThat(firstClosure.getCloseNumber()).isEqualTo(1);
        assertThat(firstClosure.getOpeningCashAmount()).isEqualByComparingTo("1500.00");
        assertThat(firstClosure.getCashSalesAmount()).isEqualByComparingTo("813.00");
        assertThat(firstClosure.getExpectedClosingCashAmount()).isEqualByComparingTo("2313.00");

        BusinessDayResponse reopened = businessDayService.reopen(userId);
        assertThat(reopened.status()).isEqualTo(BusinessDayStatus.OPEN);
        assertThat(reopened.openingCashAmount()).isEqualByComparingTo("1500.00");
        assertThat(reopened.completedSalesAmount()).isNull();
        assertThat(reopened.closedAt()).isNull();
        assertThat(reopened.closureId()).isNull();
        assertThat(reopened.closureNumber()).isNull();
        assertThat(businessDayService.hasOpenBusinessDay()).isTrue();
        businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.ANDROID_MANUAL, BUSINESS_DAY_INSTANT);
        List<BusinessDayClosure> preservedClosures = businessDayClosureRepository
                .findByBusinessDayIdOrderByCloseNumberAsc(opened.businessDayId());
        assertThat(preservedClosures).hasSize(1);
        assertThat(preservedClosures.get(0).getCloseNumber()).isEqualTo(1);
        assertThat(preservedClosures.get(0).getExpectedClosingCashAmount()).isEqualByComparingTo("2313.00");

        order("COMPLETED", OrderPaymentMethod.CASH, "400.00", LocalDateTime.of(2026, 8, 12, 10, 0));
        BusinessDayResponse secondClose = businessDayService.close(userId,
                new CloseBusinessDayRequest(new BigDecimal("2713.00")));

        assertThat(secondClose.status()).isEqualTo(BusinessDayStatus.CLOSED);
        assertThat(secondClose.openingCashAmount()).isEqualByComparingTo("1500.00");
        assertThat(secondClose.cashSalesAmount()).isEqualByComparingTo("1213.00");
        assertThat(secondClose.expectedClosingCashAmount()).isEqualByComparingTo("2713.00");
        assertThat(secondClose.closureId()).isNotEqualTo(firstClose.closureId());
        assertThat(secondClose.closureNumber()).isEqualTo(2);
        BusinessDayResponse currentAfterSecondClose = businessDayService.current().orElseThrow();
        assertThat(currentAfterSecondClose.closureId()).isEqualTo(secondClose.closureId());
        assertThat(currentAfterSecondClose.closureNumber()).isEqualTo(2);
        assertThat(currentAfterSecondClose.cashSalesAmount()).isEqualByComparingTo("1213.00");
        assertThat(currentAfterSecondClose.expectedClosingCashAmount()).isEqualByComparingTo("2713.00");

        List<BusinessDayClosure> closures = businessDayClosureRepository
                .findByBusinessDayIdOrderByCloseNumberAsc(opened.businessDayId());
        assertThat(closures).hasSize(2);
        assertThat(closures.get(0).getCloseNumber()).isEqualTo(1);
        assertThat(closures.get(0).getCashSalesAmount()).isEqualByComparingTo("813.00");
        assertThat(closures.get(0).getExpectedClosingCashAmount()).isEqualByComparingTo("2313.00");
        assertThat(closures.get(1).getCloseNumber()).isEqualTo(2);
        assertThat(closures.get(1).getCashSalesAmount()).isEqualByComparingTo("1213.00");
        assertThat(closures.get(1).getExpectedClosingCashAmount()).isEqualByComparingTo("2713.00");
        assertThat(firstClose.openingCashAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void refusesToReopenAnOpenOrPastBusinessDay() {
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("0.00")));
        assertError(() -> businessDayService.reopen(userId), BusinessDayError.BUSINESS_DAY_NOT_CLOSED);

        businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("0.00")));
        testClock.set(Instant.parse("2026-08-13T18:00:00Z"));
        assertError(() -> businessDayService.reopen(userId), BusinessDayError.BUSINESS_DAY_REOPEN_NOT_ALLOWED);
        assertThat(businessDayService.hasOpenBusinessDay()).isFalse();
    }

    @Test
    void concurrentOpeningHasOneWinner() throws Exception {
        Long otherUser = insertUser("business-day-manager");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> openAfter(start, userId));
            Future<String> second = executor.submit(() -> openAfter(start, otherUser));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("OPEN", BusinessDayError.BUSINESS_DAY_ALREADY_OPEN.name());
            assertThat(businessDayRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReopenHasOneWinnerAndLeavesTheDayOpen() throws Exception {
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("0.00")));
        businessDayService.close(userId, new CloseBusinessDayRequest(new BigDecimal("0.00")));
        Long otherUser = insertUser("business-day-reopen-manager");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> reopenAfter(start, userId));
            Future<String> second = executor.submit(() -> reopenAfter(start, otherUser));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("OPEN", BusinessDayError.BUSINESS_DAY_NOT_CLOSED.name());
            assertThat(businessDayService.hasOpenBusinessDay()).isTrue();
            assertThat(businessDayClosureRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serializesPhysicalOrderCreationBeforeCloseAndRejectsCreationAfterClose() throws Exception {
        businessDayService.open(userId, new OpenBusinessDayRequest(new BigDecimal("0.00")));
        CountDownLatch creationHasGuard = new CountDownLatch(1);
        CountDownLatch allowCreationCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creating = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.ANDROID_MANUAL, BUSINESS_DAY_INSTANT);
                creationHasGuard.countDown();
                await(allowCreationCommit);
                order("COMPLETED", OrderPaymentMethod.CASH, "10.00", LocalDateTime.of(2026, 8, 12, 7, 0));
            }));
            assertThat(creationHasGuard.await(5, TimeUnit.SECONDS)).isTrue();
            Future<BusinessDayResponse> closing = executor.submit(() -> businessDayService.close(userId,
                    new CloseBusinessDayRequest(new BigDecimal("10.00"))));

            assertThat(closing.isDone()).isFalse();
            allowCreationCommit.countDown();
            creating.get(5, TimeUnit.SECONDS);
            BusinessDayResponse closed = closing.get(5, TimeUnit.SECONDS);

            assertThat(closed.cashSalesAmount()).isEqualByComparingTo("10.00");
            assertError(() -> businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.ANDROID_MANUAL,
                    BUSINESS_DAY_INSTANT), BusinessDayError.BUSINESS_DAY_CLOSED);
        } finally {
            executor.shutdownNow();
        }
    }

    private String openAfter(CountDownLatch start, Long actor) throws InterruptedException {
        start.await();
        try {
            return businessDayService.open(actor, new OpenBusinessDayRequest(new BigDecimal("0.00"))).status().name();
        } catch (BusinessDayException exception) {
            return exception.getError().name();
        }
    }

    private String reopenAfter(CountDownLatch start, Long actor) throws InterruptedException {
        start.await();
        try {
            return businessDayService.reopen(actor).status().name();
        } catch (BusinessDayException exception) {
            return exception.getError().name();
        }
    }

    private OrderRecord order(String status, OrderPaymentMethod paymentMethod, String total, LocalDateTime createdAt) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("5214770000000");
        order.setPaymentMethod(paymentMethod);
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setTotalAmount(Double.valueOf(total));
        order.setTotalAmountAmount(new BigDecimal(total));
        order.setStatus(status);
        order.setCreatedAt(createdAt);
        order.setOrderDetails("Order evidence");
        return orderRepository.saveAndFlush(order);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrent test", exception);
        }
    }

    private Long insertUser(String username) {
        jdbcTemplate.update("""
                insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                    password_changed_at, created_at, updated_at, version)
                values (?, ?, ?, 'OWNER', true, 0, ?, ?, ?, 0)
                """, username, username, "{bcrypt}not-used", BUSINESS_DAY_INSTANT, BUSINESS_DAY_INSTANT,
                BUSINESS_DAY_INSTANT);
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                             BusinessDayError expected) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessDayException.class)
                .extracting(exception -> ((BusinessDayException) exception).getError())
                .isEqualTo(expected);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        @Primary
        TestClock fixedClock() {
            return new TestClock(BUSINESS_DAY_INSTANT);
        }

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

    static final class TestClock extends Clock {
        private volatile Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
