package com.cardovia.merkon.backend.order;

import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.checkout.CheckoutMoney;
import com.cardovia.merkon.backend.entity.OrderFulfillmentType;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderPaymentTiming;
import com.cardovia.merkon.backend.entity.OrderRecord;
import com.cardovia.merkon.backend.entity.OrderSource;
import com.cardovia.merkon.backend.repository.OrderRepository;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private BusinessDayService businessDayService;

    @Mock
    private LegacyBusinessResolver legacyBusinessResolver;

    @Test
    void persistedPickupPayOnDeliveryCanBeCollectedAtReady() {
        OrderRecord order = new OrderRecord();
        order.setId(41L);
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setFulfillmentType(OrderFulfillmentType.PICKUP);
        order.setPaymentTiming(OrderPaymentTiming.ON_DELIVERY);
        order.setStatus(OrderLifecycleStatus.READY.persistedValue());
        order.setCreatedAt(LocalDateTime.of(2026, 8, 12, 18, 0));

        when(orderRepository.findPaymentCollectionReferenceByIdAndBusinessId(order.getId(), 3L))
                .thenReturn(Optional.of(new OrderPaymentCollectionReference(order.getId(), order.getCreatedAt())));
        when(orderRepository.findByIdAndBusinessIdForUpdate(order.getId(), 3L)).thenReturn(Optional.of(order));
        OrderLifecycleService service = new OrderLifecycleService(orderRepository, legacyBusinessResolver, businessDayService,
                new CheckoutMoney(), Clock.systemUTC());

        OrderPaymentCollectionResponse response = service.collectPayment(3L, order.getId(), 7L,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null));

        assertThat(response.currentStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED);
        assertThat(order.getStatus()).isEqualTo(OrderLifecycleStatus.COMPLETED.persistedValue());
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CARD);
        assertThat(order.getCashDenomination()).isNull();
        assertThat(order.getPaymentCollectedAt()).isNotNull();
        assertThat(order.getPaymentCollectedByUserId()).isEqualTo(7L);
        verify(orderRepository).findByIdAndBusinessIdForUpdate(order.getId(), 3L);
        verify(orderRepository).flush();
    }
}
