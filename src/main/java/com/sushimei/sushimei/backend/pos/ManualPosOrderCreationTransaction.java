package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;
import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupResponse;
import com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionResponse;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.promotion.AppliedPromotionResponse;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineResponse;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteResponse;
import com.sushimei.sushimei.backend.promotion.PromotionRewardQuoteResponse;
import com.sushimei.sushimei.backend.promotion.TemporalPromotionQuoteService;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.security.AppUserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One short database transaction: re-quote, immutable evidence, then order persistence. */
@Service
class ManualPosOrderCreationTransaction {
    private final OrderRepository orderRepository;
    private final AppUserRepository appUserRepository;
    private final TemporalPromotionQuoteService promotionQuoteService;
    private final ParallelMoneyResolver parallelMoneyResolver;
    private final CheckoutMoney checkoutMoney;

    ManualPosOrderCreationTransaction(OrderRepository orderRepository,
                                      AppUserRepository appUserRepository,
                                      TemporalPromotionQuoteService promotionQuoteService,
                                      ParallelMoneyResolver parallelMoneyResolver,
                                      CheckoutMoney checkoutMoney) {
        this.orderRepository = orderRepository;
        this.appUserRepository = appUserRepository;
        this.promotionQuoteService = promotionQuoteService;
        this.parallelMoneyResolver = parallelMoneyResolver;
        this.checkoutMoney = checkoutMoney;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    ManualPosOrderResponse create(Long userId, NormalizedManualPosOrder request) {
        OrderRecord existing = orderRepository.findByClientRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            ManualPosOrderReadService.verifyOwnershipAndFingerprint(existing, userId, request.fingerprint());
            return ManualPosOrderReadService.response(existing, ManualOrderResult.ALREADY_CREATED);
        }
        appUserRepository.findById(userId).orElseThrow(() -> new ManualPosOrderException(ManualPosOrderError.ORDER_FORBIDDEN_OPERATION));
        PromotionQuoteResponse quote = promotionQuoteService.quote(new PromotionQuoteRequest(request.lines()));
        validateDeliveryCashDenomination(request, quote.total());
        OrderRecord order = createOrder(userId, request, quote);
        int linePosition = 1;
        for (PromotionQuoteLineResponse quoteLine : quote.lines()) {
            BigDecimal paidUnit = positive(quoteLine.chargedBaseUnitPrice().add(quoteLine.configuration().unitAdjustmentTotal()));
            BigDecimal paidTotal = positive(paidUnit.multiply(BigDecimal.valueOf(quoteLine.quantity())));
            AppliedPromotionResponse promotion = quoteLine.appliedPromotion();
            OrderLineRecord paid = OrderLineRecord.createManualPaid(quoteLine.lineKey(), quoteLine.menuItemId(), linePosition++, quoteLine.name(),
                    quoteLine.quantity(), quoteLine.catalogBaseUnitPrice(), quoteLine.chargedBaseUnitPrice(),
                    quoteLine.configuration().unitAdjustmentTotal(), paidUnit, paidTotal,
                    promotion == null ? null : promotion.id(), promotion == null ? null : promotion.name(),
                    promotion == null ? null : promotion.benefitType().name());
            snapshots(paid, quoteLine.configuration());
            order.addOrderLine(paid);
            for (PromotionRewardQuoteResponse reward : quoteLine.rewards()) {
                AppliedPromotionResponse rewardPromotion = reward.promotion();
                OrderLineRecord rewardLine = OrderLineRecord.createPromotionReward(paid, linePosition++, reward.name(),
                        reward.catalogBaseUnitPrice(), reward.configurationAdjustmentTotal(), nonNegative(reward.total()),
                        nonNegative(reward.total()), rewardPromotion.id(), rewardPromotion.name(),
                        rewardPromotion.benefitType().name(), reward.rewardOrdinal());
                snapshots(rewardLine, reward.configuration());
                order.addOrderLine(rewardLine);
            }
        }
        OrderRecord saved = orderRepository.saveAndFlush(order);
        return ManualPosOrderReadService.response(saved, ManualOrderResult.CREATED);
    }

    private OrderRecord createOrder(Long userId, NormalizedManualPosOrder request, PromotionQuoteResponse quote) {
        ParallelMoney money;
        try {
            money = parallelMoneyResolver.forWriteFromExact(quote.total());
        } catch (RuntimeException exception) {
            throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception);
        }
        OrderRecord order = new OrderRecord();
        order.setClientRequestId(request.requestId());
        order.setCreatedByUserId(userId);
        order.setRequestFingerprint(request.fingerprint());
        order.setOrderSource(OrderSource.ANDROID_MANUAL);
        order.setFulfillmentType(request.fulfillmentType());
        order.setPaymentMethod(request.paymentMethod());
        order.setDeliveryAddress(request.deliveryAddress());
        order.setPickupName(request.pickupName());
        order.setCashDenomination(request.cashDenomination());
        order.setDeliveryType(request.fulfillmentType().name());
        order.setPaymentNotes(request.paymentMethod().name());
        order.setTransferReceiptPath(null);
        order.setPhoneNumber(null);
        order.setTotalAmountAmount(money.numericAmount());
        order.setTotalAmount(money.legacyAmount());
        order.setStatus(request.paymentMethod() == com.sushimei.sushimei.backend.entity.OrderPaymentMethod.TRANSFER
                ? "PENDING_VALIDATION" : "PENDING");
        order.setCreatedAt(LocalDateTime.ofInstant(quote.quotedAt(), ZoneOffset.UTC));
        order.setOrderDetails(ManualPosOrderLegacyDetailsFormatter.format(quote));
        return order;
    }

    private void validateDeliveryCashDenomination(NormalizedManualPosOrder request, BigDecimal authoritativeTotal) {
        if (request.fulfillmentType() == com.sushimei.sushimei.backend.entity.OrderFulfillmentType.DELIVERY
                && request.paymentMethod() == com.sushimei.sushimei.backend.entity.OrderPaymentMethod.CASH
                && request.cashDenomination().compareTo(authoritativeTotal) < 0) {
            throw new ManualPosOrderException(ManualPosOrderError.ORDER_CASH_DENOMINATION_INSUFFICIENT);
        }
    }

    private void snapshots(OrderLineRecord line, MenuItemQuoteResponse quote) {
        for (MenuQuoteGroupResponse group : quote.groups()) {
            int position = 1;
            for (MenuQuoteSelectionResponse selection : group.selections()) {
                OrderLineSelectionSnapshot snapshot = OrderLineSelectionSnapshot.create(null, group.groupId(), group.name(),
                        position++, selection.menuItemId(), selection.name(), selection.quantity(), selection.catalogUnitPrice(),
                        selection.priceAdjustment());
                line.addSelectionSnapshot(snapshot);
                nestedSnapshots(line, snapshot, selection.groups());
            }
        }
    }

    private void nestedSnapshots(OrderLineRecord line, OrderLineSelectionSnapshot parent, java.util.List<MenuQuoteGroupResponse> groups) {
        for (MenuQuoteGroupResponse group : groups) {
            int position = 1;
            for (MenuQuoteSelectionResponse selection : group.selections()) {
                OrderLineSelectionSnapshot snapshot = OrderLineSelectionSnapshot.create(parent, group.groupId(), group.name(),
                        position++, selection.menuItemId(), selection.name(), selection.quantity(), selection.catalogUnitPrice(),
                        selection.priceAdjustment());
                line.addSelectionSnapshot(snapshot);
                nestedSnapshots(line, snapshot, selection.groups());
            }
        }
    }

    private BigDecimal positive(BigDecimal value) {
        try { return checkoutMoney.normalizeNumericAmount(value); }
        catch (IllegalArgumentException exception) { throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception); }
    }

    private BigDecimal nonNegative(BigDecimal value) {
        try { return checkoutMoney.normalizeNonNegativeNumericAmount(value); }
        catch (IllegalArgumentException exception) { throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception); }
    }
}
