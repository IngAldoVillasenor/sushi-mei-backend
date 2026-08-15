package com.sushimei.sushimei.backend.tools;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.checkout.MonetaryCompatibilityException;
import com.sushimei.sushimei.backend.checkout.MonetaryCompatibilityReason;
import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.agent.AiToolSafetyGuard;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.CartService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderToolsDualMoneyTest {

    private static final String SAFE_FAILURE_RESPONSE =
            "No se pudo procesar el carrito en este momento. Intenta nuevamente o solicita ayuda del restaurante.";

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CartService cartService = mock(CartService.class);
    private final AiMenuItemResolver menuItemResolver = mock(AiMenuItemResolver.class);
    private final OrderTools orderTools = new OrderTools(
            orderRepository, cartService, new AiToolSafetyGuard(), menuItemResolver);
    private final ParallelMoneyResolver moneyResolver = new ParallelMoneyResolver(new CheckoutMoney());

    @Test
    void confirmOrderWritesBothMoneyRepresentationsFromOneValidatedPair() {
        ParallelMoney total = moneyResolver.forWriteFromExact(new java.math.BigDecimal("21.00"));
        OrderRecord saved = new OrderRecord();
        saved.setId(7L);
        when(cartService.getCartTotalForOrder("525512345678")).thenReturn(total);
        when(cartService.getCartContents("525512345678")).thenReturn("Detalle exacto de la orden");
        when(orderRepository.save(org.mockito.ArgumentMatchers.any(OrderRecord.class))).thenReturn(saved);

        String response = orderTools.confirmOrder("525512345678", "DOMICILIO", "Calle Cinco", "Efectivo 500");

        assertThat(response).isEqualTo("La orden fue guardada. El ticket es #7"
                + ". Dile al cliente que su pedido ya est\u00e1 en preparaci\u00f3n y saldr\u00e1 a su domicilio en aproximadamente 35 a 45 minutos.");

        ArgumentCaptor<OrderRecord> captor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualTo(21.0d);
        assertThat(captor.getValue().getTotalAmountAmount()).isEqualByComparingTo("21.00");
        verify(cartService).clearCart("525512345678");
    }

    @Test
    void confirmOrderMapsUnsafeTotalToTheGenericResponseBeforePersistence() {
        when(cartService.getCartTotalForOrder("525512345678")).thenThrow(monetaryFailure());

        String response = orderTools.confirmOrder(
                "525512345678", "DOMICILIO", "Calle Cinco", "Efectivo 500");

        assertThat(response).isEqualTo(SAFE_FAILURE_RESPONSE);
        verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.any(OrderRecord.class));
        verify(cartService, never()).clearCart("525512345678");
    }

    @Test
    void addDishToCartMapsMonetaryFailureToTheGenericResponse() {
        when(menuItemResolver.resolveExact("Maki"))
                .thenReturn(new ResolvedMenuItem("Maki", new java.math.BigDecimal("10.50")));
        org.mockito.Mockito.doThrow(monetaryFailure())
                .when(cartService).addItem("525512345678", "Maki", 1, 10.5d);

        assertThat(orderTools.addDishToCart("525512345678", "Maki", 1, 10.5d))
                .isEqualTo(SAFE_FAILURE_RESPONSE);
    }

    @Test
    void checkCartMapsMonetaryFailureToTheGenericResponse() {
        when(cartService.getCartContents("525512345678")).thenThrow(monetaryFailure());

        assertThat(orderTools.checkCart("525512345678"))
                .isEqualTo(SAFE_FAILURE_RESPONSE);
    }

    @Test
    void removeDishFromCartMapsMonetaryFailureToTheGenericResponse() {
        when(cartService.removeItem("525512345678", "Maki", 1)).thenThrow(monetaryFailure());

        assertThat(orderTools.removeDishFromCart("525512345678", "Maki", 1))
                .isEqualTo(SAFE_FAILURE_RESPONSE);
    }

    private MonetaryCompatibilityException monetaryFailure() {
        return new MonetaryCompatibilityException(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION);
    }
}
