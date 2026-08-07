package com.sushimei.sushimei.backend.service;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.repository.CartRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceMutationLockingTest {

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartService cartService = new CartService(
            cartRepository, new CheckoutMoney(), new ParallelMoneyResolver(new CheckoutMoney()));

    @Test
    void activeMutationLookupDeclaresAPessimisticWriteLock() throws NoSuchMethodException {
        Method method = CartRepository.class.getMethod("findOpenCartByPhoneNumberForUpdate", String.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void addItemLocksAnExistingOpenCartBeforeMutatingIt() {
        Cart cart = openCart("5214770000201");
        when(cartRepository.findOpenCartByPhoneNumberForUpdate("5214770000201"))
                .thenReturn(Optional.of(cart));

        cartService.addItem("5214770000201", "Maki", 2, 10.5d);

        assertThat(cart.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getDishName()).isEqualTo("Maki");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("10.50");
        });
        verify(cartRepository).findOpenCartByPhoneNumberForUpdate("5214770000201");
        verify(cartRepository, never()).findByPhoneNumberAndStatus("5214770000201", "OPEN");
        verify(cartRepository).save(cart);
    }

    @Test
    void removeItemLocksAnExistingOpenCartBeforeRemovingAndOnlyReadsUnlockedForItsLegacyResponse() {
        Cart cart = openCart("5214770000202");
        cart.addItem(item("Maki", 2, 10.5d));
        when(cartRepository.findOpenCartByPhoneNumberForUpdate("5214770000202"))
                .thenReturn(Optional.of(cart));
        when(cartRepository.findByPhoneNumberAndStatus("5214770000202", "OPEN")).thenReturn(cart);

        String response = cartService.removeItem("5214770000202", "Maki", 1);

        assertThat(cart.getItems()).singleElement().satisfies(item -> assertThat(item.getQuantity()).isEqualTo(1));
        assertThat(response).contains("Platillo removido exitosamente.");
        org.mockito.InOrder order = inOrder(cartRepository);
        order.verify(cartRepository).findOpenCartByPhoneNumberForUpdate("5214770000202");
        order.verify(cartRepository).save(cart);
        order.verify(cartRepository).findByPhoneNumberAndStatus("5214770000202", "OPEN");
    }

    @Test
    void clearCartAlsoLocksTheExistingOpenCartBeforeClosingIt() {
        Cart cart = openCart("5214770000204");
        when(cartRepository.findOpenCartByPhoneNumberForUpdate("5214770000204"))
                .thenReturn(Optional.of(cart));

        cartService.clearCart("5214770000204");

        assertThat(cart.getStatus()).isEqualTo("CLOSED");
        verify(cartRepository).findOpenCartByPhoneNumberForUpdate("5214770000204");
        verify(cartRepository, never()).findByPhoneNumberAndStatus("5214770000204", "OPEN");
        verify(cartRepository).save(cart);
    }

    @Test
    void addItemCreatesANewOpenCartInsteadOfMutatingTheClosedCartWhenTheLockedLookupFindsNoOpenRow() {
        Cart closedCart = openCart("5214770000203");
        closedCart.setStatus("CLOSED");
        when(cartRepository.findOpenCartByPhoneNumberForUpdate("5214770000203"))
                .thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cartService.addItem("5214770000203", "Coca Cola", 1, 20.0d);

        assertThat(closedCart.getItems()).isEmpty();
        verify(cartRepository).findOpenCartByPhoneNumberForUpdate("5214770000203");
        verify(cartRepository, never()).findByPhoneNumberAndStatus("5214770000203", "OPEN");
        org.mockito.ArgumentCaptor<Cart> savedCarts = org.mockito.ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository, org.mockito.Mockito.times(2)).save(savedCarts.capture());
        assertThat(savedCarts.getAllValues()).allSatisfy(saved -> {
            assertThat(saved).isNotSameAs(closedCart);
            assertThat(saved.getStatus()).isEqualTo("OPEN");
        });
        assertThat(savedCarts.getValue().getItems()).singleElement().satisfies(item ->
                assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("20.00"));
    }

    private Cart openCart(String phoneNumber) {
        Cart cart = new Cart();
        cart.setPhoneNumber(phoneNumber);
        cart.setStatus("OPEN");
        return cart;
    }

    private CartItem item(String dishName, int quantity, double unitPrice) {
        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setUnitPriceAmount(BigDecimal.valueOf(unitPrice));
        return item;
    }
}
