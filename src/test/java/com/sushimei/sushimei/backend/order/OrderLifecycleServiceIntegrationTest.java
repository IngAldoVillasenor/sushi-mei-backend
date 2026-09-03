package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.businessday.BusinessDayError;
import com.sushimei.sushimei.backend.businessday.BusinessDayException;
import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.businessday.CloseBusinessDayRequest;
import com.sushimei.sushimei.backend.businessday.OpenBusinessDayRequest;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    @Autowired
    private BusinessDayService businessDayService;

    private Long voidActorUserId;

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from public.business_day_cash_expenses");
        jdbcTemplate.update("delete from public.business_day_closures");
        jdbcTemplate.update("delete from public.business_days");
        jdbcTemplate.update("delete from public.order_line_component_omissions");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        voidActorUserId = insertVoidActor();
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
    void physicalPosOrdersInEveryActiveStateCanBeVoidedWithAuditableEvidence() {
        List<OrderLifecycleStatus> activeStatuses = List.of(
                OrderLifecycleStatus.PENDING_VALIDATION,
                OrderLifecycleStatus.PENDING,
                OrderLifecycleStatus.PREPARING,
                OrderLifecycleStatus.READY);

        for (int index = 0; index < activeStatuses.size(); index++) {
            OrderLifecycleStatus status = activeStatuses.get(index);
            OrderRecord order = order(status, OrderPaymentMethod.CASH, index + 1);
            if (status == OrderLifecycleStatus.READY) {
                order.setOrderSource(OrderSource.COUNTER);
                orderRepository.saveAndFlush(order);
            }

            OrderVoidResponse response = orderLifecycleService.voidOrder(order.getId(), voidActorUserId,
                    new OrderVoidRequest("  Cliente canceló el pedido  "));
            OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();

            assertThat(response.previousStatus()).isEqualTo(status);
            assertThat(response.currentStatus()).isEqualTo(OrderLifecycleStatus.VOIDED);
            assertThat(response.voidReason()).isEqualTo("Cliente canceló el pedido");
            assertThat(response.voidedAt()).isNotNull();
            assertThat(response.voidedByUserId()).isEqualTo(voidActorUserId);
            assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.VOIDED.persistedValue());
            assertThat(persisted.getVoidReason()).isEqualTo("Cliente canceló el pedido");
            assertThat(persisted.getVoidedAt()).isNotNull();
            assertThat(persisted.getVoidedByUserId()).isEqualTo(voidActorUserId);
        }
    }

    @Test
    void voidRejectsTerminalCancelledAndNonPosOrdersWithoutPartiallyWritingAuditEvidence() {
        OrderRecord completed = order(OrderLifecycleStatus.COMPLETED, OrderPaymentMethod.CASH, 1);
        OrderRecord alreadyVoided = order(OrderLifecycleStatus.VOIDED, OrderPaymentMethod.CASH, 2);
        OrderRecord cancelled = order(OrderLifecycleStatus.CANCELLED_CLARIFICATION, OrderPaymentMethod.CASH, 3);
        OrderRecord whatsapp = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 4);
        whatsapp.setOrderSource(OrderSource.WHATSAPP_AI);
        orderRepository.saveAndFlush(whatsapp);
        OrderRecord unknown = order("LEGACY_UNKNOWN", OrderPaymentMethod.CASH, 5);

        assertError(() -> orderLifecycleService.voidOrder(completed.getId(), voidActorUserId,
                new OrderVoidRequest("Cliente canceló")), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.voidOrder(alreadyVoided.getId(), voidActorUserId,
                new OrderVoidRequest("Cliente canceló")), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.voidOrder(cancelled.getId(), voidActorUserId,
                new OrderVoidRequest("Cliente canceló")), OrderLifecycleError.ORDER_INVALID_TRANSITION);
        assertError(() -> orderLifecycleService.voidOrder(whatsapp.getId(), voidActorUserId,
                new OrderVoidRequest("Cliente canceló")), OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
        assertError(() -> orderLifecycleService.voidOrder(unknown.getId(), voidActorUserId,
                new OrderVoidRequest("Cliente canceló")), OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);

        for (OrderRecord order : List.of(completed, alreadyVoided, cancelled, whatsapp, unknown)) {
            OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(persisted.getVoidReason()).isNull();
            assertThat(persisted.getVoidedAt()).isNull();
            assertThat(persisted.getVoidedByUserId()).isNull();
        }
    }

    @Test
    void voidRejectsMissingBlankAndOverlongReasonsWithoutChangingTheOrder() {
        OrderRecord order = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 1);

        assertError(() -> orderLifecycleService.voidOrder(order.getId(), voidActorUserId, null),
                OrderLifecycleError.ORDER_INVALID_VOID_REQUEST);
        assertError(() -> orderLifecycleService.voidOrder(order.getId(), voidActorUserId, new OrderVoidRequest("   ")),
                OrderLifecycleError.ORDER_INVALID_VOID_REQUEST);
        assertError(() -> orderLifecycleService.voidOrder(order.getId(), voidActorUserId,
                new OrderVoidRequest("x".repeat(501))), OrderLifecycleError.ORDER_INVALID_VOID_REQUEST);

        OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.PENDING.persistedValue());
        assertThat(persisted.getVoidReason()).isNull();
        assertThat(persisted.getVoidedAt()).isNull();
        assertThat(persisted.getVoidedByUserId()).isNull();
    }

    @Test
    void voidedOrderIsExcludedFromTheActiveOperationalProjection() {
        OrderRecord pending = order(OrderLifecycleStatus.PENDING, OrderPaymentMethod.CASH, 1);
        OrderRecord voided = order(OrderLifecycleStatus.PREPARING, OrderPaymentMethod.CASH, 2);
        orderLifecycleService.voidOrder(voided.getId(), voidActorUserId, new OrderVoidRequest("Cliente canceló"));

        assertThat(orderLifecycleService.activeOrders()).extracting(ActiveOrderResponse::id)
                .containsExactly(pending.getId());
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

    @Test
    void concurrentCompletionAndVoidHaveExactlyOneWinnerBecauseTheyLockTheSameOrderRow() throws Exception {
        OrderRecord order = order(OrderLifecycleStatus.READY, OrderPaymentMethod.CASH, 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> completing = executor.submit(() -> completeAfter(start, order.getId()));
            Future<String> voiding = executor.submit(() -> voidAfter(start, order.getId()));
            start.countDown();

            List<String> outcomes = List.of(completing.get(), voiding.get());
            assertThat(outcomes).contains(OrderLifecycleError.ORDER_INVALID_TRANSITION.name());
            assertThat(outcomes).containsAnyOf(OrderLifecycleStatus.COMPLETED.name(), OrderLifecycleStatus.VOIDED.name());
            String persistedStatus = orderRepository.findById(order.getId()).orElseThrow().getStatus();
            assertThat(persistedStatus).isIn(OrderLifecycleStatus.COMPLETED.persistedValue(),
                    OrderLifecycleStatus.VOIDED.persistedValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readyPayOnDeliveryCollectsTheActualMethodAtomicallyAndCannotUseNormalComplete() {
        OrderRecord cash = readyPayOnDeliveryOrder(1);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(BigDecimal.ZERO));

        assertError(() -> orderLifecycleService.complete(cash.getId()),
                OrderLifecycleError.ORDER_PAYMENT_COLLECTION_REQUIRED);
        assertError(() -> orderLifecycleService.collectPayment(cash.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("9.99"))),
                OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
        assertThat(orderRepository.findById(cash.getId()).orElseThrow().getStatus()).isEqualTo("READY");
        assertThat(orderRepository.findById(cash.getId()).orElseThrow().getPaymentMethod()).isNull();

        OrderPaymentCollectionResponse cashCollected = orderLifecycleService.collectPayment(cash.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("20.00")));
        assertThat(cashCollected.currentStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED);
        assertThat(cashCollected.paymentMethod()).isEqualTo(OrderPaymentMethod.CASH);
        assertThat(cashCollected.cashDenomination()).isEqualByComparingTo("20.00");
        assertThat(cashCollected.paymentCollectedAt()).isNotNull();
        assertThat(cashCollected.paymentCollectedByUserId()).isEqualTo(voidActorUserId);

        OrderRecord transfer = readyPayOnDeliveryOrder(2);
        OrderRecord card = readyPayOnDeliveryOrder(3);
        assertError(() -> orderLifecycleService.collectPayment(transfer.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.TRANSFER, new BigDecimal("10.00"))),
                OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
        assertError(() -> orderLifecycleService.collectPayment(card.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, new BigDecimal("10.00"))),
                OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
        for (OrderRecord unpaid : List.of(transfer, card)) {
            OrderRecord persistedUnpaid = orderRepository.findById(unpaid.getId()).orElseThrow();
            assertThat(persistedUnpaid.getStatus()).isEqualTo(OrderLifecycleStatus.READY.persistedValue());
            assertThat(persistedUnpaid.getPaymentMethod()).isNull();
            assertThat(persistedUnpaid.getCashDenomination()).isNull();
            assertThat(persistedUnpaid.getPaymentCollectedAt()).isNull();
            assertThat(persistedUnpaid.getPaymentCollectedByUserId()).isNull();
        }
        assertThat(orderLifecycleService.collectPayment(transfer.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.TRANSFER, null)).paymentMethod())
                .isEqualTo(OrderPaymentMethod.TRANSFER);
        assertThat(orderLifecycleService.collectPayment(card.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null)).paymentMethod())
                .isEqualTo(OrderPaymentMethod.CARD);
        assertError(() -> orderLifecycleService.collectPayment(cash.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("20.00"))),
                OrderLifecycleError.ORDER_INVALID_TRANSITION);

        OrderRecord persisted = orderRepository.findById(cash.getId()).orElseThrow();
        assertThat(persisted.requiresPaymentCollection()).isFalse();
        assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED.persistedValue());
    }

    @Test
    void readyPickupPayOnDeliveryCollectsAndCannotUseNormalComplete() {
        OrderRecord pickup = readyPayOnDeliveryOrder(4, OrderFulfillmentType.PICKUP);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(BigDecimal.ZERO));

        assertError(() -> orderLifecycleService.complete(pickup.getId()),
                OrderLifecycleError.ORDER_PAYMENT_COLLECTION_REQUIRED);

        OrderPaymentCollectionResponse collected = orderLifecycleService.collectPayment(pickup.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null));

        assertThat(collected.currentStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED);
        assertThat(collected.paymentMethod()).isEqualTo(OrderPaymentMethod.CARD);
        assertThat(collected.cashDenomination()).isNull();
        OrderRecord persisted = orderRepository.findById(pickup.getId()).orElseThrow();
        assertThat(persisted.getFulfillmentType()).isEqualTo(OrderFulfillmentType.PICKUP);
        assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED.persistedValue());
        assertThat(persisted.requiresPaymentCollection()).isFalse();
    }

    @Test
    void concurrentPayOnDeliveryCollectionsHaveOneWinnerAndPreserveOnlyItsPaymentEvidence() throws Exception {
        OrderRecord order = readyPayOnDeliveryOrder(1);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(BigDecimal.ZERO));
        Long cardActorUserId = insertActor("lifecycle-card-collector");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CollectionAttempt> cash = executor.submit(() -> collectAfter(start, order.getId(), voidActorUserId,
                    new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("10.00"))));
            Future<CollectionAttempt> card = executor.submit(() -> collectAfter(start, order.getId(), cardActorUserId,
                    new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null)));
            start.countDown();

            List<CollectionAttempt> attempts = List.of(cash.get(5, TimeUnit.SECONDS), card.get(5, TimeUnit.SECONDS));
            assertThat(attempts).filteredOn(CollectionAttempt::isSuccess).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.isSuccess())
                    .extracting(CollectionAttempt::error)
                    .containsExactly(OrderLifecycleError.ORDER_INVALID_TRANSITION);

            CollectionAttempt winner = attempts.stream().filter(CollectionAttempt::isSuccess).findFirst().orElseThrow();
            OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED.persistedValue());
            assertThat(persisted.getPaymentMethod()).isEqualTo(winner.response().paymentMethod());
            assertThat(persisted.getPaymentCollectedAt()).isNotNull();
            assertThat(persisted.getPaymentCollectedByUserId()).isEqualTo(winner.actorUserId());
            assertThat(persisted.getVoidReason()).isNull();
            assertThat(persisted.getVoidedAt()).isNull();
            assertThat(persisted.getVoidedByUserId()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPayOnDeliveryCollectionAndVoidLeaveExactlyOneTerminalEvidenceSet() throws Exception {
        OrderRecord order = readyPayOnDeliveryOrder(1);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(BigDecimal.ZERO));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CollectionAttempt> collection = executor.submit(() -> collectAfter(start, order.getId(), voidActorUserId,
                    new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("10.00"))));
            Future<String> voiding = executor.submit(() -> voidAfter(start, order.getId()));
            start.countDown();

            CollectionAttempt collectionAttempt = collection.get(5, TimeUnit.SECONDS);
            String voidOutcome = voiding.get(5, TimeUnit.SECONDS);
            assertThat(List.of(collectionAttempt.outcome(), voidOutcome))
                    .contains(OrderLifecycleError.ORDER_INVALID_TRANSITION.name())
                    .containsAnyOf(OrderLifecycleStatus.COMPLETED.name(), OrderLifecycleStatus.VOIDED.name());

            OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
            if (OrderLifecycleStatus.COMPLETED.persistedValue().equals(persisted.getStatus())) {
                assertThat(persisted.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CASH);
                assertThat(persisted.getPaymentCollectedAt()).isNotNull();
                assertThat(persisted.getPaymentCollectedByUserId()).isEqualTo(voidActorUserId);
                assertThat(persisted.getVoidReason()).isNull();
                assertThat(persisted.getVoidedAt()).isNull();
                assertThat(persisted.getVoidedByUserId()).isNull();
            } else {
                assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.VOIDED.persistedValue());
                assertThat(persisted.getPaymentMethod()).isNull();
                assertThat(persisted.getCashDenomination()).isNull();
                assertThat(persisted.getPaymentCollectedAt()).isNull();
                assertThat(persisted.getPaymentCollectedByUserId()).isNull();
                assertThat(persisted.getVoidReason()).isNotBlank();
                assertThat(persisted.getVoidedAt()).isNotNull();
                assertThat(persisted.getVoidedByUserId()).isEqualTo(voidActorUserId);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void voidedPayOnDeliveryOrderCannotBeCollectedAndNoLongerRequiresCollection() {
        OrderRecord order = readyPayOnDeliveryOrder(1);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(BigDecimal.ZERO));

        orderLifecycleService.voidOrder(order.getId(), voidActorUserId, new OrderVoidRequest("Cliente canceló"));
        assertError(() -> orderLifecycleService.collectPayment(order.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("10.00"))),
                OrderLifecycleError.ORDER_INVALID_TRANSITION);

        OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderLifecycleStatus.VOIDED.persistedValue());
        assertThat(persisted.requiresPaymentCollection()).isFalse();
        assertThat(persisted.getPaymentMethod()).isNull();
        assertThat(persisted.getCashDenomination()).isNull();
        assertThat(persisted.getPaymentCollectedAt()).isNull();
        assertThat(persisted.getPaymentCollectedByUserId()).isNull();
    }

    @Test
    void pendingPayOnDeliveryPreventsCloseUntilCollectionAndThenContributesByCollectedMethod() {
        OrderRecord order = readyPayOnDeliveryOrder(1);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(new BigDecimal("10.00")));

        assertThatThrownBy(() -> businessDayService.close(voidActorUserId,
                new CloseBusinessDayRequest(new BigDecimal("10.00"))))
                .isInstanceOf(BusinessDayException.class)
                .extracting(exception -> ((BusinessDayException) exception).getError())
                .isEqualTo(BusinessDayError.BUSINESS_DAY_HAS_ACTIVE_ORDERS);
        assertThat(orderLifecycleService.collectPayment(order.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("10.00"))).currentStatus())
                .isEqualTo(OrderLifecycleStatus.COMPLETED);

        var closed = businessDayService.close(voidActorUserId, new CloseBusinessDayRequest(new BigDecimal("20.00")));
        assertThat(closed.completedSalesAmount()).isEqualByComparingTo("10.00");
        assertThat(closed.cashSalesAmount()).isEqualByComparingTo("10.00");
        assertThat(closed.expectedClosingCashAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void collectionRejectsMissingOpenDayImmediateAndNonReadyOrdersWithoutPartialPaymentMutation() {
        OrderRecord noOpenDay = readyPayOnDeliveryOrder(1);
        assertError(() -> orderLifecycleService.collectPayment(noOpenDay.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null)),
                OrderLifecycleError.ORDER_PAYMENT_COLLECTION_BUSINESS_DAY_NOT_OPEN);

        OrderRecord immediate = readyPayOnDeliveryOrder(2);
        immediate.setPaymentTiming(OrderPaymentTiming.IMMEDIATE);
        immediate.setPaymentMethod(OrderPaymentMethod.CASH);
        orderRepository.saveAndFlush(immediate);
        businessDayService.open(voidActorUserId, new OpenBusinessDayRequest(BigDecimal.ZERO));
        assertError(() -> orderLifecycleService.collectPayment(immediate.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CASH, new BigDecimal("10.00"))),
                OrderLifecycleError.ORDER_PAYMENT_COLLECTION_NOT_SUPPORTED);
        OrderRecord preparing = readyPayOnDeliveryOrder(3);
        preparing.setStatus(OrderLifecycleStatus.PREPARING.persistedValue());
        orderRepository.saveAndFlush(preparing);
        assertError(() -> orderLifecycleService.collectPayment(preparing.getId(), voidActorUserId,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null)),
                OrderLifecycleError.ORDER_INVALID_TRANSITION);

        for (OrderRecord order : List.of(noOpenDay, immediate, preparing)) {
            OrderRecord persisted = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(persisted.getPaymentCollectedAt()).isNull();
            assertThat(persisted.getPaymentCollectedByUserId()).isNull();
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

    private String completeAfter(CountDownLatch start, Long orderId) throws InterruptedException {
        start.await();
        try {
            return orderLifecycleService.complete(orderId).currentStatus().name();
        } catch (OrderLifecycleException exception) {
            return exception.getError().name();
        }
    }

    private String voidAfter(CountDownLatch start, Long orderId) throws InterruptedException {
        start.await();
        try {
            return orderLifecycleService.voidOrder(orderId, voidActorUserId, new OrderVoidRequest("Cliente canceló"))
                    .currentStatus().name();
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
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 11, 10, minute));
        order.setOrderDetails("Orden de prueba");
        return orderRepository.saveAndFlush(order);
    }

    private CollectionAttempt collectAfter(CountDownLatch start, Long orderId, Long actorUserId,
                                           OrderPaymentCollectionRequest request) throws InterruptedException {
        start.await();
        try {
            return new CollectionAttempt(actorUserId, orderLifecycleService.collectPayment(orderId, actorUserId, request), null);
        } catch (OrderLifecycleException exception) {
            return new CollectionAttempt(actorUserId, null, exception.getError());
        }
    }

    private OrderRecord readyPayOnDeliveryOrder(int sequence) {
        return readyPayOnDeliveryOrder(sequence, OrderFulfillmentType.DELIVERY);
    }

    private OrderRecord readyPayOnDeliveryOrder(int sequence, OrderFulfillmentType fulfillmentType) {
        java.time.LocalDate businessDate = java.time.Instant.now().atZone(ZoneId.of("America/Mexico_City")).toLocalDate();
        LocalDateTime createdAt = businessDate.atTime(12, sequence).atZone(ZoneId.of("America/Mexico_City"))
                .toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
        OrderRecord order = new OrderRecord();
        order.setPhoneNumber("521477200" + sequence);
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setFulfillmentType(fulfillmentType);
        order.setPaymentTiming(OrderPaymentTiming.ON_DELIVERY);
        if (fulfillmentType == OrderFulfillmentType.PICKUP) {
            order.setPickupName("Cliente " + sequence);
        } else {
            order.setDeliveryAddress("Calle " + sequence);
        }
        order.setTotalAmount(10.00d);
        order.setTotalAmountAmount(new BigDecimal("10.00"));
        order.setStatus(OrderLifecycleStatus.READY.persistedValue());
        order.setCreatedAt(createdAt);
        order.setOrderDetails("Pago al entregar");
        return orderRepository.saveAndFlush(order);
    }

    private Long insertVoidActor() {
        return insertActor("lifecycle-void-actor");
    }

    private Long insertActor(String username) {
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from public.app_users where username = ?", Integer.class, username);
        if (existing == null || existing == 0) {
            jdbcTemplate.update("""
                    insert into public.app_users (username, display_name, password_hash, role, active, failed_login_attempts,
                        password_changed_at, created_at, updated_at, version)
                    values (?, ?, ?, 'CASHIER', true, 0, current_timestamp, current_timestamp, current_timestamp, 0)
                    """, username, username, "{bcrypt}not-used");
        }
        return jdbcTemplate.queryForObject("select id from public.app_users where username = ?", Long.class, username);
    }

    private record CollectionAttempt(Long actorUserId, OrderPaymentCollectionResponse response,
                                     OrderLifecycleError error) {
        boolean isSuccess() {
            return response != null;
        }

        String outcome() {
            return isSuccess() ? response.currentStatus().name() : error.name();
        }
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
