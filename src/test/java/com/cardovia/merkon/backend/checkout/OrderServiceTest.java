package com.cardovia.merkon.backend.checkout;

import com.cardovia.merkon.backend.conversation.ConversationSessionRepository;
import com.cardovia.merkon.backend.conversation.ConversationStateMachine;
import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import com.cardovia.merkon.backend.entity.Cart;
import com.cardovia.merkon.backend.entity.OrderSource;
import com.cardovia.merkon.backend.repository.CartRepository;
import com.cardovia.merkon.backend.repository.OrderRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ConversationSessionRepository conversationSessionRepository;
    @Mock
    private CartSnapshotService cartSnapshotService;
    @Mock
    private ConversationStateMachine conversationStateMachine;
    @Mock
    private ParallelMoneyResolver parallelMoneyResolver;
    @Mock
    private CheckoutMoney checkoutMoney;
    @Mock
    private Clock clock;
    @Mock
    private BusinessDayService businessDayService;
    @Mock
    private LegacyBusinessResolver legacyBusinessResolver;

    @InjectMocks
    private OrderService orderService;

    @Test
    void invalidPersistedCartMoneyStopsBeforeAnyOrderOrConversationMutation() {
        Cart cart = new Cart();
        cart.setId(10L);
        cart.setPhoneNumber("5214770000199");
        cart.setStatus("OPEN");
        CheckoutCompletionCommand command = new CheckoutCompletionCommand(
                "5214770000199", 10L, OrderSource.WHATSAPP_AI);
        InvalidCartItemException invalidMoney = new InvalidCartItemException(
                InvalidCartItemReason.INVALID_NUMERIC_UNIT_PRICE);

        Business business = org.mockito.Mockito.mock(Business.class);
        when(business.getId()).thenReturn(3L);
        when(legacyBusinessResolver.requireLegacyBusiness()).thenReturn(business);
        when(orderRepository.findByBusinessIdAndSourceCartId(3L, 10L)).thenReturn(Optional.empty());
        when(cartRepository.findByIdAndBusinessIdForUpdate(10L, 3L)).thenReturn(Optional.of(cart));
        when(cartSnapshotService.snapshotOf(cart)).thenThrow(invalidMoney);

        assertThatThrownBy(() -> orderService.completeCheckout(command)).isSameAs(invalidMoney);

        verify(cartRepository).findByIdAndBusinessIdForUpdate(10L, 3L);
        verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(conversationSessionRepository, never()).findByPhoneNumberAndBusinessId(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(cart.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void exactCartLookupIsDeclaredAsAPessimisticWriteLock() throws NoSuchMethodException {
        Method method = CartRepository.class.getMethod("findByIdAndBusinessIdForUpdate", Long.class, Long.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
