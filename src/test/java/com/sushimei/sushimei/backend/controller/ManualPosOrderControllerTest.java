package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.pos.ManualOrderResult;
import com.sushimei.sushimei.backend.pos.ManualPosOrderError;
import com.sushimei.sushimei.backend.pos.ManualPosOrderException;
import com.sushimei.sushimei.backend.pos.ManualPosOrderRequest;
import com.sushimei.sushimei.backend.pos.ManualPosOrderResponse;
import com.sushimei.sushimei.backend.pos.ManualPosOrderService;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import java.math.BigDecimal;
import java.time.Instant;
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
                null, "PENDING", Instant.parse("2026-08-11T12:00:00Z"), List.of(), new BigDecimal("79.00"));
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("7");
        when(service.create(7L, request)).thenReturn(response);

        var entity = controller.create(jwt, request);

        assertThat(entity.getStatusCode().value()).isEqualTo(201);
        assertThat(entity.getHeaders().getLocation()).isNull();
        assertThat(entity.getBody()).isSameAs(response);
        assertThat(entity.getBody().createdAt()).isEqualTo(Instant.parse("2026-08-11T12:00:00Z"));
    }

    @Test
    void insufficientDeliveryCashUsesTheDedicatedSafeApiError() {
        ManualPosOrderApiExceptionHandler handler = new ManualPosOrderApiExceptionHandler();

        var response = handler.manual(new ManualPosOrderException(
                ManualPosOrderError.ORDER_CASH_DENOMINATION_INSUFFICIENT));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("ORDER_CASH_DENOMINATION_INSUFFICIENT");
        assertThat(response.getBody().message()).isEqualTo("La denominación en efectivo es menor al total de la orden.");
    }
}
