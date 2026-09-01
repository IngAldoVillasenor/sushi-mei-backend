package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
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

    @Test
    void persistedPickupPayOnDeliveryIsRejectedDefensivelyBeforeAnyPaymentMutation() {
        OrderRecord order = new OrderRecord();
        order.setId(41L);
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setFulfillmentType(OrderFulfillmentType.PICKUP);
        order.setPaymentTiming(OrderPaymentTiming.ON_DELIVERY);
        order.setStatus(OrderLifecycleStatus.READY.persistedValue());
        order.setCreatedAt(LocalDateTime.of(2026, 8, 12, 18, 0));

        when(orderRepository.findPaymentCollectionReferenceById(order.getId()))
                .thenReturn(Optional.of(new OrderPaymentCollectionReference(order.getId(), order.getCreatedAt())));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        OrderLifecycleService service = new OrderLifecycleService(orderRepository, businessDayService,
                new CheckoutMoney(), Clock.systemUTC());

        assertThatThrownBy(() -> service.collectPayment(order.getId(), 7L,
                new OrderPaymentCollectionRequest(OrderPaymentMethod.CARD, null)))
                .isInstanceOf(OrderLifecycleException.class)
                .extracting(exception -> ((OrderLifecycleException) exception).getError())
                .isEqualTo(OrderLifecycleError.ORDER_PAYMENT_COLLECTION_NOT_SUPPORTED);

        assertThat(order.getStatus()).isEqualTo(OrderLifecycleStatus.READY.persistedValue());
        assertThat(order.getPaymentMethod()).isNull();
        assertThat(order.getCashDenomination()).isNull();
        assertThat(order.getPaymentCollectedAt()).isNull();
        assertThat(order.getPaymentCollectedByUserId()).isNull();
        verify(orderRepository).findByIdForUpdate(order.getId());
        verify(orderRepository, never()).flush();
    }
}
