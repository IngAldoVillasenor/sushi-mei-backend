package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.pos.ManualOrderResult;
import com.sushimei.sushimei.backend.pos.ManualPosOrderRequest;
import com.sushimei.sushimei.backend.pos.ManualPosOrderResponse;
import com.sushimei.sushimei.backend.pos.ManualPosOrderService;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManualPosOrderControllerTest {

    @Test
    void createdManualOrderReturns201WithoutAFalseLocationHeader() {
        ManualPosOrderService service = mock(ManualPosOrderService.class);
        ManualPosOrderController controller = new ManualPosOrderController(service);
        UUID requestId = UUID.randomUUID();
        ManualPosOrderRequest request = new ManualPosOrderRequest(requestId, OrderFulfillmentType.PICKUP,
                OrderPaymentMethod.CASH, null, "Ana", new BigDecimal("100.00"),
                List.of(new PromotionQuoteLineRequest("line", 1L, 1, List.of(), List.of())));
        ManualPosOrderResponse response = new ManualPosOrderResponse(44L, requestId, ManualOrderResult.CREATED,
                OrderSource.ANDROID_MANUAL, 7L, OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana",
                new BigDecimal("100.00"), "PENDING", LocalDateTime.of(2026, 8, 11, 12, 0), List.of(), new BigDecimal("79.00"));
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("7");
        when(service.create(7L, request)).thenReturn(response);

        var entity = controller.create(jwt, request);

        assertThat(entity.getStatusCode().value()).isEqualTo(201);
        assertThat(entity.getHeaders().getLocation()).isNull();
        assertThat(entity.getBody()).isSameAs(response);
    }
}
