package com.sushimei.sushimei.backend.conversation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

@Component
public class ConversationStateMachine {

    static final int DELIVERY_ADDRESS_MIN_LENGTH = 5;
    static final int DELIVERY_ADDRESS_MAX_LENGTH = 500;
    static final int PICKUP_NAME_MIN_LENGTH = 2;
    static final int PICKUP_NAME_MAX_LENGTH = 120;
    static final int TRANSFER_RECEIPT_PATH_MAX_LENGTH = 1024;
    static final int CASH_PRECISION = 19;
    static final int CASH_SCALE = 2;

    public ConversationSession requestCheckoutReview(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.REQUEST_CHECKOUT_REVIEW, ConversationState.ORDERING);
        session.beginCartConfirmation(now);
        return session;
    }

    public ConversationSession confirmCart(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.CONFIRM_CART,
                ConversationState.WAITING_CART_CONFIRMATION);
        session.confirmCart(now);
        return session;
    }

    public ConversationSession continueOrdering(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.CONTINUE_ORDERING,
                ConversationState.WAITING_CART_CONFIRMATION);
        session.returnToOrdering(now);
        return session;
    }

    public ConversationSession selectDelivery(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.SELECT_DELIVERY,
                ConversationState.WAITING_FULFILLMENT_TYPE);
        session.selectDelivery(now);
        return session;
    }

    public ConversationSession selectPickup(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.SELECT_PICKUP,
                ConversationState.WAITING_FULFILLMENT_TYPE);
        session.selectPickup(now);
        return session;
    }

    public ConversationSession provideDeliveryAddress(ConversationSession session, String address, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS,
                ConversationState.WAITING_DELIVERY_ADDRESS);
        requireFulfillment(session, ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS, FulfillmentType.DELIVERY);
        String normalizedAddress = normalizeAddress(session, address);
        session.captureDeliveryAddress(normalizedAddress, now);
        return session;
    }

    public ConversationSession providePickupName(ConversationSession session, String pickupName, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.PROVIDE_PICKUP_NAME,
                ConversationState.WAITING_PICKUP_NAME);
        requireFulfillment(session, ConversationTransitionAction.PROVIDE_PICKUP_NAME, FulfillmentType.PICKUP);
        String normalizedPickupName = normalizePickupName(session, pickupName);
        session.capturePickupName(normalizedPickupName, now);
        return session;
    }

    public ConversationSession selectCash(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.SELECT_CASH,
                ConversationState.WAITING_PAYMENT_METHOD);
        validateFulfillmentDetails(session, ConversationTransitionAction.SELECT_CASH);
        session.selectCash(now);
        return session;
    }

    public ConversationSession selectTransfer(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.SELECT_TRANSFER,
                ConversationState.WAITING_PAYMENT_METHOD);
        validateFulfillmentDetails(session, ConversationTransitionAction.SELECT_TRANSFER);
        session.selectTransfer(now);
        return session;
    }

    public ConversationSession selectCard(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.SELECT_CARD,
                ConversationState.WAITING_PAYMENT_METHOD);
        validateReadyToConfirmInvariants(session, ConversationTransitionAction.SELECT_CARD,
                PaymentMethod.CARD, null, null);
        session.selectPickupCard(now);
        return session;
    }

    public ConversationSession provideCashDenomination(ConversationSession session,
                                                        BigDecimal denomination,
                                                        Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                ConversationState.WAITING_CASH_DENOMINATION);
        requirePaymentMethod(session, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION, PaymentMethod.CASH);
        BigDecimal normalizedDenomination = normalizeCashDenomination(session, denomination);
        validateReadyToConfirmInvariants(session, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                PaymentMethod.CASH, normalizedDenomination, null);
        session.captureCashDenomination(normalizedDenomination, now);
        return session;
    }

    public ConversationSession provideTransferReceipt(ConversationSession session,
                                                       String receiptPath,
                                                       Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT,
                ConversationState.WAITING_TRANSFER_RECEIPT);
        requirePaymentMethod(session, ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT,
                PaymentMethod.TRANSFER);
        String normalizedReceiptPath = normalizeReceiptPath(session, receiptPath);
        validateReadyToConfirmInvariants(session, ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT,
                PaymentMethod.TRANSFER, null, normalizedReceiptPath);
        session.captureTransferReceipt(normalizedReceiptPath, now);
        return session;
    }

    public ConversationSession confirmOrder(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.CONFIRM_ORDER, ConversationState.READY_TO_CONFIRM);
        validateReadyToConfirmInvariants(session, ConversationTransitionAction.CONFIRM_ORDER,
                session.getPaymentMethod(), session.getCashDenomination(), session.getTransferReceiptPath());
        session.confirmCheckout(now);
        return session;
    }

    public ConversationSession cancelCheckout(ConversationSession session, Instant now) {
        requireTimestamp(now);
        requireState(session, ConversationTransitionAction.CANCEL_CHECKOUT,
                ConversationState.ORDERING,
                ConversationState.WAITING_CART_CONFIRMATION,
                ConversationState.WAITING_FULFILLMENT_TYPE,
                ConversationState.WAITING_DELIVERY_ADDRESS,
                ConversationState.WAITING_PICKUP_NAME,
                ConversationState.WAITING_PAYMENT_METHOD,
                ConversationState.WAITING_CASH_DENOMINATION,
                ConversationState.WAITING_TRANSFER_RECEIPT,
                ConversationState.READY_TO_CONFIRM);
        session.cancelCheckout(now);
        return session;
    }

    private void validateReadyToConfirmInvariants(ConversationSession session,
                                                  ConversationTransitionAction action,
                                                  PaymentMethod paymentMethod,
                                                  BigDecimal cashDenomination,
                                                  String transferReceiptPath) {
        validateFulfillmentDetails(session, action);
        if (paymentMethod == null) {
            throw invalid(session, action, allowedState(action));
        }

        switch (paymentMethod) {
            case CASH -> {
                if (!isValidCashDenomination(cashDenomination)) {
                    throw invalid(session, action, allowedState(action));
                }
            }
            case TRANSFER -> {
                if (!isValidText(transferReceiptPath, 1, TRANSFER_RECEIPT_PATH_MAX_LENGTH)) {
                    throw invalid(session, action, allowedState(action));
                }
            }
            case CARD -> {
                if (session.getFulfillmentType() != FulfillmentType.PICKUP) {
                    throw invalid(session, action, allowedState(action));
                }
            }
        }
    }

    private void validateFulfillmentDetails(ConversationSession session, ConversationTransitionAction action) {
        FulfillmentType fulfillmentType = session.getFulfillmentType();
        if (fulfillmentType == null) {
            throw invalid(session, action, allowedState(action));
        }
        if (fulfillmentType == FulfillmentType.DELIVERY
                && !isValidText(session.getDeliveryAddress(), DELIVERY_ADDRESS_MIN_LENGTH, DELIVERY_ADDRESS_MAX_LENGTH)) {
            throw invalid(session, action, allowedState(action));
        }
        if (fulfillmentType == FulfillmentType.PICKUP
                && !isValidText(session.getPickupName(), PICKUP_NAME_MIN_LENGTH, PICKUP_NAME_MAX_LENGTH)) {
            throw invalid(session, action, allowedState(action));
        }
    }

    private void requireFulfillment(ConversationSession session,
                                    ConversationTransitionAction action,
                                    FulfillmentType expectedFulfillmentType) {
        if (session.getFulfillmentType() != expectedFulfillmentType) {
            throw invalid(session, action, allowedState(action));
        }
    }

    private void requirePaymentMethod(ConversationSession session,
                                      ConversationTransitionAction action,
                                      PaymentMethod expectedPaymentMethod) {
        if (session.getPaymentMethod() != expectedPaymentMethod) {
            throw invalid(session, action, allowedState(action));
        }
    }

    private String normalizeAddress(ConversationSession session, String address) {
        return normalizeText(session, ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS, address,
                DELIVERY_ADDRESS_MIN_LENGTH, DELIVERY_ADDRESS_MAX_LENGTH);
    }

    private String normalizePickupName(ConversationSession session, String pickupName) {
        return normalizeText(session, ConversationTransitionAction.PROVIDE_PICKUP_NAME, pickupName,
                PICKUP_NAME_MIN_LENGTH, PICKUP_NAME_MAX_LENGTH);
    }

    private String normalizeReceiptPath(ConversationSession session, String receiptPath) {
        return normalizeText(session, ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT, receiptPath,
                1, TRANSFER_RECEIPT_PATH_MAX_LENGTH);
    }

    private String normalizeText(ConversationSession session,
                                 ConversationTransitionAction action,
                                 String value,
                                 int minimumLength,
                                 int maximumLength) {
        if (value == null) {
            throw invalid(session, action, allowedState(action));
        }
        String normalizedValue = value.trim();
        if (!isValidText(normalizedValue, minimumLength, maximumLength)) {
            throw invalid(session, action, allowedState(action));
        }
        return normalizedValue;
    }

    private BigDecimal normalizeCashDenomination(ConversationSession session, BigDecimal denomination) {
        if (denomination == null || denomination.signum() <= 0) {
            throw invalid(session, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                    allowedState(ConversationTransitionAction.PROVIDE_CASH_DENOMINATION));
        }
        try {
            BigDecimal normalizedDenomination = denomination.setScale(CASH_SCALE, RoundingMode.UNNECESSARY);
            if (normalizedDenomination.precision() > CASH_PRECISION) {
                throw invalid(session, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                        allowedState(ConversationTransitionAction.PROVIDE_CASH_DENOMINATION));
            }
            return normalizedDenomination;
        } catch (ArithmeticException exception) {
            throw invalid(session, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                    allowedState(ConversationTransitionAction.PROVIDE_CASH_DENOMINATION));
        }
    }

    private boolean isValidCashDenomination(BigDecimal denomination) {
        if (denomination == null || denomination.signum() <= 0) {
            return false;
        }
        try {
            BigDecimal normalizedDenomination = denomination.setScale(CASH_SCALE, RoundingMode.UNNECESSARY);
            return normalizedDenomination.precision() <= CASH_PRECISION;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private boolean isValidText(String value, int minimumLength, int maximumLength) {
        return value != null
                && !value.trim().isEmpty()
                && value.trim().length() >= minimumLength
                && value.trim().length() <= maximumLength;
    }

    private void requireTimestamp(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
    }
    private void requireState(ConversationSession session,
                              ConversationTransitionAction action,
                              ConversationState... allowedSourceStates) {
        Objects.requireNonNull(session, "session must not be null");
        Set<ConversationState> allowedStates = EnumSet.copyOf(java.util.List.of(allowedSourceStates));
        if (!allowedStates.contains(session.getState())) {
            throw invalid(session, action, allowedStates);
        }
    }

    private Set<ConversationState> allowedState(ConversationTransitionAction action) {
        return switch (action) {
            case REQUEST_CHECKOUT_REVIEW -> EnumSet.of(ConversationState.ORDERING);
            case CONFIRM_CART, CONTINUE_ORDERING -> EnumSet.of(ConversationState.WAITING_CART_CONFIRMATION);
            case SELECT_DELIVERY, SELECT_PICKUP -> EnumSet.of(ConversationState.WAITING_FULFILLMENT_TYPE);
            case PROVIDE_DELIVERY_ADDRESS -> EnumSet.of(ConversationState.WAITING_DELIVERY_ADDRESS);
            case PROVIDE_PICKUP_NAME -> EnumSet.of(ConversationState.WAITING_PICKUP_NAME);
            case SELECT_CASH, SELECT_TRANSFER, SELECT_CARD -> EnumSet.of(ConversationState.WAITING_PAYMENT_METHOD);
            case PROVIDE_CASH_DENOMINATION -> EnumSet.of(ConversationState.WAITING_CASH_DENOMINATION);
            case PROVIDE_TRANSFER_RECEIPT -> EnumSet.of(ConversationState.WAITING_TRANSFER_RECEIPT);
            case CONFIRM_ORDER -> EnumSet.of(ConversationState.READY_TO_CONFIRM);
            case CANCEL_CHECKOUT -> EnumSet.of(
                    ConversationState.ORDERING,
                    ConversationState.WAITING_CART_CONFIRMATION,
                    ConversationState.WAITING_FULFILLMENT_TYPE,
                    ConversationState.WAITING_DELIVERY_ADDRESS,
                    ConversationState.WAITING_PICKUP_NAME,
                    ConversationState.WAITING_PAYMENT_METHOD,
                    ConversationState.WAITING_CASH_DENOMINATION,
                    ConversationState.WAITING_TRANSFER_RECEIPT,
                    ConversationState.READY_TO_CONFIRM);
        };
    }

    private InvalidConversationTransitionException invalid(ConversationSession session,
                                                            ConversationTransitionAction action,
                                                            Set<ConversationState> allowedSourceStates) {
        return new InvalidConversationTransitionException(session.getState(), action, allowedSourceStates);
    }
}
