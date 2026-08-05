package com.sushimei.sushimei.backend.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@Service
public class ConversationTransitionService {

    private final ConversationSessionRepository conversationSessionRepository;
    private final ConversationStateMachine conversationStateMachine;
    private final Clock clock;

    public ConversationTransitionService(ConversationSessionRepository conversationSessionRepository,
                                         ConversationStateMachine conversationStateMachine,
                                         Clock clock) {
        this.conversationSessionRepository = conversationSessionRepository;
        this.conversationStateMachine = conversationStateMachine;
        this.clock = clock;
    }

    @Transactional
    public ConversationSession requestCheckoutReview(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = conversationSessionRepository.findById(normalizedPhoneNumber)
                .orElseGet(() -> ConversationSession.create(normalizedPhoneNumber, now));
        conversationStateMachine.requestCheckoutReview(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession confirmCart(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.CONFIRM_CART);
        conversationStateMachine.confirmCart(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession continueOrdering(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.CONTINUE_ORDERING);
        conversationStateMachine.continueOrdering(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession selectDelivery(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.SELECT_DELIVERY);
        conversationStateMachine.selectDelivery(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession selectPickup(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.SELECT_PICKUP);
        conversationStateMachine.selectPickup(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession provideDeliveryAddress(String phoneNumber, String address) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber,
                ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS);
        conversationStateMachine.provideDeliveryAddress(session, address, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession providePickupName(String phoneNumber, String pickupName) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber,
                ConversationTransitionAction.PROVIDE_PICKUP_NAME);
        conversationStateMachine.providePickupName(session, pickupName, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession selectCash(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.SELECT_CASH);
        conversationStateMachine.selectCash(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession selectTransfer(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.SELECT_TRANSFER);
        conversationStateMachine.selectTransfer(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession selectCard(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber, ConversationTransitionAction.SELECT_CARD);
        conversationStateMachine.selectCard(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession provideCashDenomination(String phoneNumber, BigDecimal denomination) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber,
                ConversationTransitionAction.PROVIDE_CASH_DENOMINATION);
        conversationStateMachine.provideCashDenomination(session, denomination, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession provideTransferReceipt(String phoneNumber, String receiptPath) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber,
                ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT);
        conversationStateMachine.provideTransferReceipt(session, receiptPath, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession confirmCheckout(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber,
                ConversationTransitionAction.CONFIRM_CHECKOUT);
        conversationStateMachine.confirmCheckout(session, now);
        return conversationSessionRepository.save(session);
    }

    @Transactional
    public ConversationSession cancelCheckout(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        Instant now = clock.instant();
        ConversationSession session = requiredSession(normalizedPhoneNumber,
                ConversationTransitionAction.CANCEL_CHECKOUT);
        conversationStateMachine.cancelCheckout(session, now);
        return conversationSessionRepository.save(session);
    }

    private ConversationSession requiredSession(String phoneNumber, ConversationTransitionAction action) {
        return conversationSessionRepository.findById(phoneNumber)
                .orElseThrow(() -> new ConversationSessionNotFoundException(action));
    }

    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber must not be blank");
        }
        return phoneNumber.trim();
    }
}