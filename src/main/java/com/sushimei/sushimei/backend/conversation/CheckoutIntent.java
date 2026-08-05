package com.sushimei.sushimei.backend.conversation;

import java.math.BigDecimal;

public sealed interface CheckoutIntent permits CheckoutIntent.RequestCheckoutReview,
        CheckoutIntent.ConfirmCart,
        CheckoutIntent.ContinueOrdering,
        CheckoutIntent.SelectFulfillment,
        CheckoutIntent.ProvideDeliveryAddress,
        CheckoutIntent.ProvidePickupName,
        CheckoutIntent.SelectPaymentMethod,
        CheckoutIntent.ProvideCashDenomination,
        CheckoutIntent.ProvideTransferReceipt,
        CheckoutIntent.ConfirmCheckout,
        CheckoutIntent.CancelCheckout {

    record RequestCheckoutReview() implements CheckoutIntent {
    }

    record ConfirmCart() implements CheckoutIntent {
    }

    record ContinueOrdering() implements CheckoutIntent {
    }

    record SelectFulfillment(FulfillmentType fulfillmentType) implements CheckoutIntent {
    }

    record ProvideDeliveryAddress(String address) implements CheckoutIntent {
    }

    record ProvidePickupName(String pickupName) implements CheckoutIntent {
    }

    record SelectPaymentMethod(PaymentMethod paymentMethod) implements CheckoutIntent {
    }

    record ProvideCashDenomination(BigDecimal denomination) implements CheckoutIntent {
    }

    record ProvideTransferReceipt(String receiptPath) implements CheckoutIntent {
    }

    record ConfirmCheckout() implements CheckoutIntent {
    }

    record CancelCheckout() implements CheckoutIntent {
    }
}
