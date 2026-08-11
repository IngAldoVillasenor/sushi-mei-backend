package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
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
        OrderRepository orders = mock(OrderRepository.class);
        AiConversationService ai = mock(AiConversationService.class);
        WhatsAppService whatsApp = mock(WhatsAppService.class);
        CartService carts = mock(CartService.class);
        OrderRecord order = new OrderRecord();
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        when(orders.findById(10L)).thenReturn(Optional.of(order));
        OrderController controller = new OrderController(orders, ai, whatsApp, carts);

        var response = controller.rejectOrder(10L, Map.of("reason", "test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(ai, whatsApp, carts);
    }
}
