package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.entity.OrderRecord;
import java.time.LocalDateTime;

/** Stable operational projection for Kitchen/POS clients; never exposes OrderRecord directly. */
public record ActiveOrderResponse(
        Long id,
        String phoneNumber,
        String deliveryType,
        String deliveryAddress,
        String transferReceiptPath,
        String paymentNotes,
        String orderDetails,
        Double totalAmount,
        String status,
        LocalDateTime createdAt
) {
    static ActiveOrderResponse from(OrderRecord order) {
        return new ActiveOrderResponse(
                order.getId(),
                order.getPhoneNumber(),
                order.getDeliveryType(),
                order.getDeliveryAddress(),
                order.getTransferReceiptPath(),
                order.getPaymentNotes(),
                order.getOrderDetails(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
