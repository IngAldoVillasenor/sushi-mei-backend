package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.repository.CartRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CartSnapshotServiceTest {

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartSnapshotService cartSnapshotService = new CartSnapshotService(cartRepository, new CheckoutMoney(), new ParallelMoneyResolver(new CheckoutMoney()));

    @Test
    void rejectsAnUnpersistedCartItemThroughThePureMapper() {
        CartItem item = new CartItem();
        item.setDishName("Maki");
        item.setQuantity(1);
        item.setUnitPrice(10.0d);

        assertThatThrownBy(() -> cartSnapshotService.toLineSnapshot(item))
                .isInstanceOf(InvalidCartItemException.class)
                .extracting(exception -> ((InvalidCartItemException) exception).getReason())
                .isEqualTo(InvalidCartItemReason.MISSING_ITEM_ID);
    }

    @Test
    void readsWithoutWritingOrCallingLegacyCartServices() {
        Cart cart = new Cart();
        cart.setId(7L);
        cart.setPhoneNumber("525512345678");
        cart.setStatus("OPEN");

        CartItem item = new CartItem();
        item.setId(9L);
        item.setDishName(" Maki ");
        item.setQuantity(2);
        item.setUnitPrice(10.5d);
        cart.addItem(item);

        when(cartRepository.findAllByPhoneNumberAndStatusOrderByIdAsc("525512345678", "OPEN"))
                .thenReturn(List.of(cart));

        CartSnapshot snapshot = cartSnapshotService.readActiveCart(" 525512345678 ");

        assertThat(snapshot.cartId()).isEqualTo(7L);
        assertThat(snapshot.items()).singleElement().satisfies(line -> {
            assertThat(line.dishName()).isEqualTo("Maki");
            assertThat(line.lineTotal()).isEqualByComparingTo("21.00");
        });
        verify(cartRepository).findAllByPhoneNumberAndStatusOrderByIdAsc("525512345678", "OPEN");
        verifyNoMoreInteractions(cartRepository);
    }
}
