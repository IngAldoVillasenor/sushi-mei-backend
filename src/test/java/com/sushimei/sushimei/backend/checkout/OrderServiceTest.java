package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.conversation.ConversationSessionRepository;
import com.sushimei.sushimei.backend.conversation.ConversationStateMachine;
import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.CartRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
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

        when(orderRepository.findBySourceCartId(10L)).thenReturn(Optional.empty());
        when(cartRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(cart));
        when(cartSnapshotService.snapshotOf(cart)).thenThrow(invalidMoney);

        assertThatThrownBy(() -> orderService.completeCheckout(command)).isSameAs(invalidMoney);

        verify(cartRepository).findByIdForUpdate(10L);
        verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(conversationSessionRepository, never()).findById(org.mockito.ArgumentMatchers.anyString());
        assertThat(cart.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void exactCartLookupIsDeclaredAsAPessimisticWriteLock() throws NoSuchMethodException {
        Method method = CartRepository.class.getMethod("findByIdForUpdate", Long.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
