package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.order.OrderLifecycleError;
import com.sushimei.sushimei.backend.order.OrderLifecycleException;
import com.sushimei.sushimei.backend.order.OrderLifecycleService;
import com.sushimei.sushimei.backend.service.CartService;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManualPosOrderLegacyGuardTest {

    @Test
    void cartlessManualOrderNeverEntersLegacyCartReopenOrWhatsAppRejectionFlow() {
        AiConversationService ai = mock(AiConversationService.class);
        WhatsAppService whatsApp = mock(WhatsAppService.class);
        CartService carts = mock(CartService.class);
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        when(lifecycle.rejectForLegacyClarification(10L))
                .thenThrow(new OrderLifecycleException(OrderLifecycleError.ORDER_OPERATION_NOT_SUPPORTED));
        OrderController controller = new OrderController(Optional.of(ai), Optional.of(whatsApp), carts, lifecycle);

        var response = controller.rejectOrder(10L, Map.of("reason", "test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(ai, whatsApp, carts);
    }

    @Test
    void disabledLegacyOrchestrationDoesNotTransitionOrReopenAnything() {
        CartService carts = mock(CartService.class);
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        OrderController controller = new OrderController(Optional.empty(), Optional.empty(), carts, lifecycle);

        var response = controller.rejectOrder(10L, Map.of("reason", "test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(carts, lifecycle);
    }
}
