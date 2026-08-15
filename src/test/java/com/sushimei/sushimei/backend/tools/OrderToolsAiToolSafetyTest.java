package com.sushimei.sushimei.backend.tools;

import com.sushimei.sushimei.backend.agent.AiToolSafetyGuard;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.CartService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderToolsAiToolSafetyTest {

    @Test
    void blocksUnsafeAgentToolCallsBeforeAnyCartOrOrderMutation() {
        CartService cartService = mock(CartService.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools orderTools = new OrderTools(orderRepository, cartService, guard);

        String greetingAdd = guard.withinTextTurn("Hola", () -> orderTools.addDishToCart("5214770000001", "Ramen Tonkotsu", 1, 100.0));
        String menuCheck = guard.withinTextTurn("¿Qué venden?", () -> orderTools.checkCart("5214770000001"));
        String genericAdd = guard.withinTextTurn("Ponme un rollo y una bebida", () -> orderTools.addDishToCart("5214770000001", "Rollo Empanizado", 1, 100.0));
        String pronounAdd = guard.withinTextTurn("Agrégamelo", () -> orderTools.addDishToCart("5214770000001", "Aderezo ranch", 1, 20.0));
        String finishConfirmation = guard.withinTextTurn("Ya sería todo", () ->
                orderTools.confirmOrder("5214770000001", "DOMICILIO", "Calle Cinco", "Efectivo 500"));

        assertThat(greetingAdd).contains("No agregues productos");
        assertThat(menuCheck).contains("no solicitó consultar el carrito");
        assertThat(genericAdd).contains("No agregues productos");
        assertThat(pronounAdd).contains("No agregues productos");
        assertThat(finishConfirmation).contains("No confirmes ni declares una orden creada");
        verifyNoInteractions(cartService, orderRepository);
    }

    @Test
    void permitsOnlyTheNamedProductAndOneExplicitCartQuery() {
        CartService cartService = mock(CartService.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools orderTools = new OrderTools(orderRepository, cartService, guard);
        when(cartService.getCartContents("5214770000001")).thenReturn("Carrito");
        when(cartService.removeItem("5214770000001", "Coca Cola", 1)).thenReturn("El carrito est\u00e1 vac\u00edo.");

        String addResponse = guard.withinTextTurn("Quiero un California", () ->
                orderTools.addDishToCart("5214770000001", "California Roll", 1, 100.0));
        String firstCartResponse = guard.withinTextTurn("¿Qué llevo?", () -> orderTools.checkCart("5214770000001"));
        String secondCartResponse = guard.withinTextTurn("¿Qué llevo?", () -> {
            orderTools.checkCart("5214770000001");
            return orderTools.checkCart("5214770000001");
        });
        String removeResponse = guard.withinTextTurn("Quita la Coca Cola", () ->
                orderTools.removeDishFromCart("5214770000001", "Coca Cola", 1));

        assertThat(addResponse).contains("California Roll", "Listo");
        assertThat(firstCartResponse).isEqualTo("Carrito");
        assertThat(secondCartResponse).contains("no solicitó consultar el carrito");
        assertThat(removeResponse).contains("Coca Cola", "El carrito est\u00e1 vac\u00edo");
        verify(cartService).addItem("5214770000001", "California Roll", 1, 100.0);
        verify(cartService).removeItem("5214770000001", "Coca Cola", 1);
        verify(cartService, times(3)).getCartContents("5214770000001");
        verifyNoInteractions(orderRepository);
    }
}
