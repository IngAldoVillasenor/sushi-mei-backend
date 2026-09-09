package com.cardovia.merkon.backend.service;

import com.cardovia.merkon.backend.checkout.CheckoutMoney;
import com.cardovia.merkon.backend.checkout.MonetaryCompatibilityException;
import com.cardovia.merkon.backend.checkout.MonetaryCompatibilityReason;
import com.cardovia.merkon.backend.checkout.ParallelMoney;
import com.cardovia.merkon.backend.checkout.ParallelMoneyResolver;
import com.cardovia.merkon.backend.entity.Cart;
import com.cardovia.merkon.backend.entity.CartItem;
import com.cardovia.merkon.backend.repository.CartRepository;
import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartServiceDualMoneyTest {

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final LegacyBusinessResolver legacyBusinessResolver = mock(LegacyBusinessResolver.class);
    private final Business business = mock(Business.class);
    private final ParallelMoneyResolver moneyResolver = new ParallelMoneyResolver(new CheckoutMoney());
    private final CartService cartService = new CartService(cartRepository, new CheckoutMoney(), moneyResolver,
            legacyBusinessResolver);

    @BeforeEach
    void tenant() {
        when(business.getId()).thenReturn(3L);
        when(legacyBusinessResolver.requireLegacyBusiness()).thenReturn(business);
        when(legacyBusinessResolver.requireLegacyBusinessId()).thenReturn(3L);
    }

    @Test
    void newCartItemDualWritesTheValidatedLegacyAndNumericRepresentations() {
        Cart activeCart = activeCart("525512345678");
        when(cartRepository.findOpenCartByBusinessIdAndPhoneNumberForUpdate(3L, "525512345678")).thenReturn(java.util.Optional.of(activeCart));

        cartService.addItem("525512345678", "Maki", 2, 10.5d);

        CartItem savedItem = activeCart.getItems().get(0);
        assertThat(savedItem.getUnitPrice()).isEqualTo(10.5d);
        assertThat(savedItem.getUnitPriceAmount()).isEqualByComparingTo("10.50");
        verify(cartRepository).save(activeCart);
    }

    @Test
    void invalidLegacyInputDoesNotCreateOrPersistAnEmptyCart() {
        assertThatThrownBy(() -> cartService.addItem("525512345678", "Maki", 1, Double.NaN))
                .isInstanceOf(MonetaryCompatibilityException.class)
                .extracting(exception -> ((MonetaryCompatibilityException) exception).getReason())
                .isEqualTo(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION);

        verifyNoInteractions(cartRepository);
    }

    @Test
    void cartContentsPreservesTheLegacyTicketFormatForValidMoney() {
        Cart activeCart = activeCart("525512345678");
        activeCart.addItem(item("Maki", 2, 10.5d, new BigDecimal("10.50")));
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(activeCart);

        assertThat(cartService.getCartContents("525512345678"))
                .isEqualTo("Detalle exacto de la orden:\n"
                        + "- 2x Maki ($10.5 c/u) = $21.0\n"
                        + "\nTOTAL A PAGAR: $21.0 MXN");
    }
    @Test
    void strictOrderTotalReturnsOneRoundTripSafePair() {
        Cart activeCart = activeCart("525512345678");
        activeCart.addItem(item("Maki", 2, 10.5d, new BigDecimal("10.50")));
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(activeCart);

        ParallelMoney total = cartService.getCartTotalForOrder("525512345678");

        assertThat(total.numericAmount()).isEqualByComparingTo("21.00");
        assertThat(total.legacyAmount()).isEqualTo(21.0d);
    }

    @Test
    void strictOrderTotalRejectsAnUnsafeLegacyRoundTrip() {
        Cart activeCart = activeCart("525512345678");
        activeCart.addItem(item("Maki", 1, null, new BigDecimal("99999999999999.99")));
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(activeCart);

        assertThatThrownBy(() -> cartService.getCartTotalForOrder("525512345678"))
                .isInstanceOf(MonetaryCompatibilityException.class)
                .extracting(exception -> ((MonetaryCompatibilityException) exception).getReason())
                .isEqualTo(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION);
    }

    @Test
    void numericOnlyReopenCloneWritesBothRepresentationsWhenRoundTripIsSafe() {
        Cart closed = cartWithStatus("525512345678", "CLOSED");
        Cart current = activeCart("525512345678");
        current.addItem(item("Maki", 1, null, new BigDecimal("10.50")));
        when(cartRepository.findFirstByBusinessIdAndPhoneNumberAndStatusOrderByIdDesc(3L, "525512345678", "CLOSED")).thenReturn(closed);
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(current);

        cartService.reopenCart("525512345678");

        CartItem clone = closed.getItems().get(0);
        assertThat(clone.getUnitPrice()).isEqualTo(10.5d);
        assertThat(clone.getUnitPriceAmount()).isEqualByComparingTo("10.50");
        verify(cartRepository).delete(current);
        verify(cartRepository).save(closed);
    }

    @Test
    void unsafeNumericOnlyReopenCloneFailsBeforeCartMutationIsPersisted() {
        Cart closed = cartWithStatus("525512345678", "CLOSED");
        Cart current = activeCart("525512345678");
        current.addItem(item("Maki", 1, null, new BigDecimal("99999999999999.99")));
        when(cartRepository.findFirstByBusinessIdAndPhoneNumberAndStatusOrderByIdDesc(3L, "525512345678", "CLOSED")).thenReturn(closed);
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(current);

        assertThatThrownBy(() -> cartService.reopenCart("525512345678"))
                .isInstanceOf(MonetaryCompatibilityException.class)
                .extracting(exception -> ((MonetaryCompatibilityException) exception).getReason())
                .isEqualTo(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION);

        assertThat(closed.getItems()).isEmpty();
        verify(cartRepository, never()).delete(current);
        verify(cartRepository, never()).save(closed);
    }

    @Test
    void sameDishPriceDisagreementIsRejectedWithoutCartMutation() {
        Cart closed = cartWithStatus("525512345678", "CLOSED");
        closed.addItem(item("Maki", 1, 10.5d, new BigDecimal("10.50")));
        Cart current = activeCart("525512345678");
        current.addItem(item("Maki", 2, 11.0d, new BigDecimal("11.00")));
        when(cartRepository.findFirstByBusinessIdAndPhoneNumberAndStatusOrderByIdDesc(3L, "525512345678", "CLOSED")).thenReturn(closed);
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(current);

        assertThatThrownBy(() -> cartService.reopenCart("525512345678"))
                .isInstanceOf(CartReopenException.class)
                .extracting(exception -> ((CartReopenException) exception).getReason())
                .isEqualTo(CartReopenFailureReason.UNIT_PRICE_MISMATCH);

        assertThat(closed.getItems()).singleElement().satisfies(item ->
                assertThat(item.getQuantity()).isEqualTo(1));
        verify(cartRepository, never()).delete(current);
        verify(cartRepository, never()).save(closed);
    }

    @Test
    void validThenInvalidReopenInputDoesNotMutateBeforeValidationCompletes() {
        Cart closed = cartWithStatus("525512345678", "CLOSED");
        Cart current = activeCart("525512345678");
        current.addItem(item("Valid", 1, null, new BigDecimal("10.50")));
        current.addItem(item("Invalid", 1, null, new BigDecimal("99999999999999.99")));
        when(cartRepository.findFirstByBusinessIdAndPhoneNumberAndStatusOrderByIdDesc(3L, "525512345678", "CLOSED")).thenReturn(closed);
        when(cartRepository.findByBusinessIdAndPhoneNumberAndStatus(3L, "525512345678", "OPEN")).thenReturn(current);

        assertThatThrownBy(() -> cartService.reopenCart("525512345678"))
                .isInstanceOf(MonetaryCompatibilityException.class);

        assertThat(closed.getItems()).isEmpty();
        verify(cartRepository, never()).delete(current);
        verify(cartRepository, never()).save(closed);
    }
    private Cart activeCart(String phoneNumber) {
        return cartWithStatus(phoneNumber, "OPEN");
    }

    private Cart cartWithStatus(String phoneNumber, String status) {
        Cart cart = new Cart();
        cart.setPhoneNumber(phoneNumber);
        cart.setStatus(status);
        return cart;
    }

    private CartItem item(String dishName, int quantity, Double legacy, BigDecimal numeric) {
        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(legacy);
        item.setUnitPriceAmount(numeric);
        return item;
    }
}
