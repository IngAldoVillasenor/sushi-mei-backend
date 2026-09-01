package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.businessday.BusinessDayError;
import com.sushimei.sushimei.backend.businessday.BusinessDayException;
import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-authoritative boundary for short, database-only operational order transitions.
 * The legacy rejection controller performs external customer/cart orchestration only after
 * its database cancellation transition has committed.
 */
@Service
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final BusinessDayService businessDayService;
    private final CheckoutMoney checkoutMoney;
    private final Clock clock;

    public OrderLifecycleService(OrderRepository orderRepository,
                                 BusinessDayService businessDayService,
                                 CheckoutMoney checkoutMoney,
                                 Clock clock) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.businessDayService = Objects.requireNonNull(businessDayService, "businessDayService must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<ActiveOrderResponse> activeOrders() {
        return orderRepository.findByStatusInOrderByCreatedAtAscIdAsc(OrderLifecycleStatus.activePersistedValues())
                .stream()
                .map(ActiveOrderResponse::from)
                .toList();
    }

    @Transactional
    public OrderLifecycleTransitionResult validatePayment(Long orderId) {
        OrderRecord order = lockRequired(orderId);
        OrderLifecycleStatus current = requiredStatus(order);
        if (order.getPaymentMethod() != OrderPaymentMethod.TRANSFER) {
            throw failure(OrderLifecycleError.ORDER_PAYMENT_NOT_VALIDATABLE);
        }
        return transition(order, current, OrderLifecycleStatus.PENDING_VALIDATION, OrderLifecycleStatus.PENDING);
    }

    @Transactional
    public OrderLifecycleTransitionResult prepare(Long orderId) {
        OrderRecord order = lockRequired(orderId);
        return transition(order, requiredStatus(order), OrderLifecycleStatus.PENDING, OrderLifecycleStatus.PREPARING);
    }

    @Transactional
    public OrderLifecycleTransitionResult complete(Long orderId) {
        OrderRecord order = lockRequired(orderId);
        OrderLifecycleStatus current = requiredStatus(order);
        if (current == OrderLifecycleStatus.READY && order.requiresPaymentCollection()) {
            throw failure(OrderLifecycleError.ORDER_PAYMENT_COLLECTION_REQUIRED);
        }
        return transition(order, current, OrderLifecycleStatus.READY, OrderLifecycleStatus.COMPLETED);
    }

    /** Collects the real final method and completes a READY delivery order in the same transaction. */
    @Transactional
    public OrderPaymentCollectionResponse collectPayment(Long orderId, Long actorUserId,
                                                          OrderPaymentCollectionRequest request) {
        if (actorUserId == null || actorUserId <= 0) {
            throw failure(OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
        }
        OrderPaymentCollectionReference reference = requiredPaymentCollectionReference(orderId);
        assertBusinessDayOpen(reference);

        OrderRecord order = lockRequired(orderId);
        requirePaymentCollectionSupported(order);
        OrderLifecycleStatus current = requireReadyPendingPaymentCollection(order);

        PaymentCollectionInput input = validatedCollectionInput(request, order);
        Instant collectedAt = clock.instant();
        order.setPaymentMethod(input.paymentMethod());
        order.setCashDenomination(input.cashDenomination());
        order.setPaymentCollectedAt(collectedAt);
        order.setPaymentCollectedByUserId(actorUserId);
        order.setStatus(OrderLifecycleStatus.COMPLETED.persistedValue());
        orderRepository.flush();
        return new OrderPaymentCollectionResponse(order.getId(), current, OrderLifecycleStatus.COMPLETED,
                order.getPaymentTiming(), order.getPaymentMethod(), order.getCashDenomination(),
                order.getPaymentCollectedAt(), order.getPaymentCollectedByUserId());
    }

    @Transactional
    public OrderLifecycleTransitionResult ready(Long orderId) {
        OrderRecord order = lockRequired(orderId);
        return transition(order, requiredStatus(order), OrderLifecycleStatus.PREPARING, OrderLifecycleStatus.READY);
    }

    /**
     * Voids a physical POS order before it becomes a completed sale. This is deliberately separate
     * from the legacy WhatsApp/cart rejection workflow.
     */
    @Transactional
    public OrderVoidResponse voidOrder(Long orderId, Long actorUserId, OrderVoidRequest request) {
        String reason = normalizedVoidReason(request);
        if (actorUserId == null || actorUserId <= 0) {
            throw failure(OrderLifecycleError.ORDER_INVALID_VOID_REQUEST);
        }

        OrderRecord order = lockRequired(orderId);
        if (!isPhysicalPosSource(order.getOrderSource())) {
            throw failure(OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
        }

        OrderLifecycleStatus current = requiredStatus(order);
        if (current != OrderLifecycleStatus.PENDING_VALIDATION
                && current != OrderLifecycleStatus.PENDING
                && current != OrderLifecycleStatus.PREPARING
                && current != OrderLifecycleStatus.READY) {
            throw failure(OrderLifecycleError.ORDER_INVALID_TRANSITION);
        }

        Instant now = clock.instant();
        order.setStatus(OrderLifecycleStatus.VOIDED.persistedValue());
        order.setVoidReason(reason);
        order.setVoidedAt(now);
        order.setVoidedByUserId(actorUserId);
        orderRepository.flush();
        return new OrderVoidResponse(order.getId(), current, OrderLifecycleStatus.VOIDED,
                order.getVoidReason(), order.getVoidedAt(), order.getVoidedByUserId());
    }

    /**
     * Preserves the legacy rejection side effect boundary without allowing cart-less POS orders
     * into the WhatsApp/cart-reopen workflow. It deliberately does no external work.
     */
    @Transactional
    public LegacyOrderRejectionResult rejectForLegacyClarification(Long orderId) {
        OrderRecord order = lockRequired(orderId);
        if (order.getOrderSource() == OrderSource.ANDROID_MANUAL) {
            throw failure(OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
        }
        OrderLifecycleStatus current = requiredStatus(order);
        if (current == OrderLifecycleStatus.COMPLETED
                || current == OrderLifecycleStatus.CANCELLED_CLARIFICATION
                || current == OrderLifecycleStatus.VOIDED) {
            throw failure(OrderLifecycleError.ORDER_INVALID_TRANSITION);
        }
        order.setStatus(OrderLifecycleStatus.CANCELLED_CLARIFICATION.persistedValue());
        orderRepository.flush();
        return new LegacyOrderRejectionResult(order.getId(), order.getPhoneNumber());
    }

    private OrderLifecycleTransitionResult transition(OrderRecord order,
                                                       OrderLifecycleStatus current,
                                                       OrderLifecycleStatus expected,
                                                       OrderLifecycleStatus target) {
        if (current != expected) {
            throw failure(OrderLifecycleError.ORDER_INVALID_TRANSITION);
        }
        order.setStatus(target.persistedValue());
        orderRepository.flush();
        return new OrderLifecycleTransitionResult(order.getId(), current, target);
    }

    private OrderRecord lockRequired(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw failure(OrderLifecycleError.ORDER_NOT_FOUND);
        }
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> failure(OrderLifecycleError.ORDER_NOT_FOUND));
    }

    private OrderPaymentCollectionReference requiredPaymentCollectionReference(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw failure(OrderLifecycleError.ORDER_NOT_FOUND);
        }
        return orderRepository.findPaymentCollectionReferenceById(orderId)
                .orElseThrow(() -> failure(OrderLifecycleError.ORDER_NOT_FOUND));
    }

    private void assertBusinessDayOpen(OrderPaymentCollectionReference reference) {
        try {
            if (reference.createdAt() == null) {
                throw failure(OrderLifecycleError.ORDER_PAYMENT_COLLECTION_BUSINESS_DAY_NOT_OPEN);
            }
            businessDayService.assertOpenBusinessDayForOperationalSettlement(
                    reference.createdAt().toInstant(java.time.ZoneOffset.UTC));
        } catch (BusinessDayException exception) {
            if (exception.getError() == BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED) {
                throw failure(OrderLifecycleError.ORDER_PAYMENT_COLLECTION_BUSINESS_DAY_NOT_OPEN);
            }
            throw exception;
        }
    }

    private void requirePaymentCollectionSupported(OrderRecord order) {
        if (!isPhysicalPosSource(order.getOrderSource())
                || order.getFulfillmentType() != OrderFulfillmentType.DELIVERY
                || order.getPaymentTiming() != OrderPaymentTiming.ON_DELIVERY) {
            throw failure(OrderLifecycleError.ORDER_PAYMENT_COLLECTION_NOT_SUPPORTED);
        }
    }

    private OrderLifecycleStatus requireReadyPendingPaymentCollection(OrderRecord order) {
        OrderLifecycleStatus current = requiredStatus(order);
        if (current != OrderLifecycleStatus.READY || !order.requiresPaymentCollection()
                || order.getPaymentCollectedAt() != null || order.getPaymentCollectedByUserId() != null) {
            throw failure(OrderLifecycleError.ORDER_INVALID_TRANSITION);
        }
        return current;
    }

    private PaymentCollectionInput validatedCollectionInput(OrderPaymentCollectionRequest request, OrderRecord order) {
        if (request == null || request.paymentMethod() == null) {
            throw failure(OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
        }
        if (request.paymentMethod() == OrderPaymentMethod.CASH) {
            try {
                java.math.BigDecimal denomination = checkoutMoney.normalizeNumericAmount(request.cashDenomination());
                java.math.BigDecimal total = checkoutMoney.normalizeNumericAmount(order.getTotalAmountAmount());
                if (denomination.compareTo(total) < 0) {
                    throw failure(OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
                }
                return new PaymentCollectionInput(OrderPaymentMethod.CASH, denomination);
            } catch (IllegalArgumentException exception) {
                throw failure(OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
            }
        }
        if ((request.paymentMethod() != OrderPaymentMethod.TRANSFER && request.paymentMethod() != OrderPaymentMethod.CARD)
                || request.cashDenomination() != null) {
            throw failure(OrderLifecycleError.ORDER_INVALID_PAYMENT_COLLECTION_REQUEST);
        }
        return new PaymentCollectionInput(request.paymentMethod(), null);
    }

    private OrderLifecycleStatus requiredStatus(OrderRecord order) {
        try {
            return OrderLifecycleStatus.fromPersisted(order.getStatus());
        } catch (IllegalArgumentException exception) {
            throw failure(OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
        }
    }

    private static boolean isPhysicalPosSource(OrderSource source) {
        return source == OrderSource.ANDROID_MANUAL || source == OrderSource.COUNTER;
    }

    private static String normalizedVoidReason(OrderVoidRequest request) {
        if (request == null || request.reason() == null) {
            throw failure(OrderLifecycleError.ORDER_INVALID_VOID_REQUEST);
        }
        String normalized = request.reason().trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw failure(OrderLifecycleError.ORDER_INVALID_VOID_REQUEST);
        }
        return normalized;
    }

    private static OrderLifecycleException failure(OrderLifecycleError error) {
        return new OrderLifecycleException(error);
    }

    private record PaymentCollectionInput(OrderPaymentMethod paymentMethod, java.math.BigDecimal cashDenomination) {
    }
}
