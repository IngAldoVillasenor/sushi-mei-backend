package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        OrderLifecycleServiceIntegrationTest.TestInfrastructureConfiguration.class})
class OrderLifecycleServiceIntegrationTest {

    @Autowired
    private OrderLifecycleService orderLifecycleService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void pendingPreparesThenReadyCompletes() {
        OrderRecord order = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 1);

        assertThat(orderLifecycleService.prepare(order.getId()).currentStatus()).isEqualTo(OrderLifecycleStatus.PREPARING);
        assertThat(orderLifecycleService.ready(order.getId()).currentStatus()).isEqualTo(OrderLifecycleStatus.READY);
        assertThat(orderLifecycleService.complete(order.getId()).currentStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void skippedAndRepeatedReadyTransitionsAreRejected() {
        OrderRecord pendingValidation = order(OrderLifecycleStatus.PENDING_VALIDATION, OrderPaymentMethod.TRANSFER, 1);
        OrderRecord pending = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 2);
        OrderRecord preparing = order(OrderLifecycleStatus.PREPARING, OrderPaymentMethod.CASH, 3);
        OrderRecord ready = order(OrderLifecycleStatus.READY, OrderPaymentMethod.CASH, 4);
        OrderRecord completed = order(OrderLifecycleStatus.COMPLETED, OrderPaymentMethod.CASH, 5);

        assertError(() -> orderLifecycleService.prepare(pendingValidation.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.complete(pending.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.ready(pending.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.complete(preparing.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.ready(ready.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.ready(completed.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertThat(pendingValidation.getStatus()).isEqualTo("PENDING_VALIDATION");
        assertThat(pending.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void terminalAndCancelledOrdersCannotReturnToActiveLifecycle() {
        OrderRecord completed = order(OrderLifecycleStatus.COMPLETED, OrderPaymentMethod.CASH, 1);
        OrderRecord cancelled = order(OrderLifecycleStatus.CANCELLED_CLARIFICATION, OrderPaymentMethod.CASH, 2);

        assertError(() -> orderLifecycleService.prepare(completed.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.prepare(cancelled.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.complete(completed.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.ready(completed.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
    }

    @Test
    void validatesOnlyPendingTransferPaymentsAndRejectsAlreadyValidatedOrders() {
        OrderRecord transfer = order(OrderLifecycleStatus.PENDING_VALIDATION, OrderPaymentMethod.TRANSFER, 1);
        OrderRecord cash = order(OrderLifecycleStatus.PENDING_VALIDATION, OrderPaymentMethod.CASH, 2);
        OrderRecord card = order(OrderLifecycleStatus.PENDING_VALIDATION, OrderPaymentMethod.CARD, 3);
        OrderRecord missingPayment = order(OrderLifecycleStatus.PENDING_VALIDATION, null, 4);

        assertThat(orderLifecycleService.validatePayment(transfer.getId()).currentStatus()).isEqualTo(OrderLifecycleStatus.PENDING);
        assertError(() -> orderLifecycleService.validatePayment(cash.getId()), OrderLifecycleError.ORDER_PAYMENT_NOT_VALIDATABLE);
        assertError(() -> orderLifecycleService.validatePayment(card.getId()), OrderLifecycleError.ORDER_PAYMENT_NOT_VALIDATABLE);
        assertError(() -> orderLifecycleService.validatePayment(missingPayment.getId()), OrderLifecycleError.ORDER_PAYMENT_NOT_VALIDATABLE);
        assertError(() -> orderLifecycleService.validatePayment(transfer.getId()), OrderLifecycleError.ORDER_INVALID_TRANSITION);
    }

    @Test
    void missingAndUnknownHistoricalStateFailSafely() {
        OrderRecord unknown = order("LEGACY_UNKNOWN", OrderPaymentMethod.CASH, 1);

        assertError(() -> orderLifecycleService.prepare(999999L), OrderLifecycleError.ORDER_NOT_FOUND);
        assertError(() -> orderLifecycleService.prepare(unknown.getId()), OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
    }

    @Test
    void androidManualOrderRemainsBlockedFromTheLegacyRejectionWorkflow() {
        OrderRecord manualOrder = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 1);
        manualOrder.setOrderSource(OrderSource.ANDROID_MANUAL);
        orderRepository.saveAndFlush(manualOrder);

        assertError(() -> orderLifecycleService.rejectForLegacyClarification(manualOrder.getId()),
                OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
        assertThat(orderRepository.findById(manualOrder.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void activeOrdersAreOldestFirstAndUseTheTypedProjection() {
        OrderRecord newer = order(OrderLifecycleStatus.PREPARING, OrderPaymentMethod.CASH, 2);
        OrderRecord older = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 1);
        OrderRecord ready = order(OrderLifecycleStatus.READY, OrderPaymentMethod.CASH, 3);
        order(OrderLifecycleStatus.COMPLETED, OrderPaymentMethod.CASH, 4);

        List<ActiveOrderResponse> active = orderLifecycleService.activeOrders();

        assertThat(active).extracting(ActiveOrderResponse::id).containsExactly(older.getId(), newer.getId(), ready.getId());
        assertThat(active).allMatch(response -> response instanceof ActiveOrderResponse);
    }

    @Test
    void conflictingConcurrentPrepareCommandsHaveOneWinnerBecauseTheyLockTheSameOrderRow() throws Exception {
        OrderRecord order = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> prepareAfter(start, order.getId()));
            Future<String> second = executor.submit(() -> prepareAfter(start, order.getId()));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("PREPARING", OrderLifecycleError.ORDER_INVALID_TRANSITION.name());
            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("PREPARING");
        } finally {
            executor.shutdownNow();
        }
    }

    private String prepareAfter(CountDownLatch start, Long orderId) throws InterruptedException {
        start.await();
        try {
            return orderLifecycleService.prepare(orderId).currentStatus().name();
        } catch (OrderLifecycleException exception) {
            return exception.getError().name();
        }
    }

    private OrderRecord order(OrderLifecycleStatus status, OrderPaymentMethod paymentMethod, int minute) {
        return order(status.persistedValue(), paymentMethod, minute);
    }

    private OrderRecord order(String status, OrderPaymentMethod paymentMethod, int minute) {
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("521477000" + minute);
        order.setPaymentMethod(paymentMethod);
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 10, minute));
        order.setOrderDetails("Orden de prueba");
        return orderRepository.saveAndFlush(order);
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
                             OrderLifecycleError expected) {
        assertThatThrownBy(operation)
                .isInstanceOf(OrderLifecycleException.class)
                .extracting(exception -> ((OrderLifecycleException) exception).getError())
                .isEqualTo(expected);
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
