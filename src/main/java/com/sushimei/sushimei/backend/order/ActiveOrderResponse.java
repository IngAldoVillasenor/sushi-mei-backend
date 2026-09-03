package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
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
        OrderPaymentMethod paymentMethod,
        OrderPaymentTiming paymentTiming,
        boolean requiresPaymentCollection,
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
                order.getPaymentMethod(),
                order.getPaymentTiming(),
                order.requiresPaymentCollection(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
