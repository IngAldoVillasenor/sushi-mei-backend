package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
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

    public OrderLifecycleService(OrderRepository orderRepository) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
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
        return transition(order, requiredStatus(order), OrderLifecycleStatus.PREPARING, OrderLifecycleStatus.COMPLETED);
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
        if (current == OrderLifecycleStatus.COMPLETED || current == OrderLifecycleStatus.CANCELLED_CLARIFICATION) {
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

    private OrderLifecycleStatus requiredStatus(OrderRecord order) {
        try {
            return OrderLifecycleStatus.fromPersisted(order.getStatus());
        } catch (IllegalArgumentException exception) {
            throw failure(OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED);
        }
    }

    private static OrderLifecycleException failure(OrderLifecycleError error) {
        return new OrderLifecycleException(error);
    }
}
