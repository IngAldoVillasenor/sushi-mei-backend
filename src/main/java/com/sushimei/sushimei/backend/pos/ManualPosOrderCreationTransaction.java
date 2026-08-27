package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;
import com.sushimei.sushimei.backend.catalog.MenuItemDefaultComponent;
import com.sushimei.sushimei.backend.catalog.MenuItemComponentService;
import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupResponse;
import com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionResponse;
import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderLineSelectionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderLineComponentOmissionSnapshot;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.promotion.AppliedPromotionResponse;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineResponse;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteResponse;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import com.sushimei.sushimei.backend.promotion.PromotionRewardConfigurationRequest;
import com.sushimei.sushimei.backend.promotion.PromotionRewardQuoteResponse;
import com.sushimei.sushimei.backend.promotion.TemporalPromotionQuoteService;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.security.AppUserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
    private final BusinessDayService businessDayService;
    private final MenuItemComponentService menuItemComponentService;
    private final Clock clock;

    ManualPosOrderCreationTransaction(OrderRepository orderRepository,
                                      AppUserRepository appUserRepository,
                                      TemporalPromotionQuoteService promotionQuoteService,
                                      ParallelMoneyResolver parallelMoneyResolver,
                                      CheckoutMoney checkoutMoney,
                                      BusinessDayService businessDayService,
                                      MenuItemComponentService menuItemComponentService,
                                      Clock clock) {
        this.orderRepository = orderRepository;
        this.appUserRepository = appUserRepository;
        this.promotionQuoteService = promotionQuoteService;
        this.parallelMoneyResolver = parallelMoneyResolver;
        this.checkoutMoney = checkoutMoney;
        this.businessDayService = businessDayService;
        this.menuItemComponentService = menuItemComponentService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    ManualPosOrderResponse create(Long userId, NormalizedManualPosOrder request) {
        OrderRecord existing = orderRepository.findByClientRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            ManualPosOrderReadService.verifyOwnershipAndFingerprint(existing, userId, request.fingerprint());
            return ManualPosOrderReadService.response(existing, ManualOrderResult.ALREADY_CREATED);
        }
        appUserRepository.findById(userId).orElseThrow(() -> new ManualPosOrderException(ManualPosOrderError.ORDER_FORBIDDEN_OPERATION));
        PromotionQuoteResponse quote = request.lines().isEmpty()
                ? emptyQuote(clock.instant())
                : promotionQuoteService.quote(new PromotionQuoteRequest(request.lines()));
        BigDecimal manualTotal = request.manualLines().stream().map(NormalizedManualPricedLine::lineTotal)
                .reduce(zero(), (left, right) -> positive(left.add(right)));
        BigDecimal total = positive(quote.total().add(manualTotal));
        validateDeliveryCashDenomination(request, total);
        businessDayService.assertPhysicalOrderCreationAllowed(OrderSource.ANDROID_MANUAL, quote.quotedAt());
        OrderRecord order = createOrder(userId, request, quote, total);
        java.util.Map<String, PromotionQuoteLineRequest> requestsByLineKey = request.lines().stream()
                .collect(java.util.stream.Collectors.toMap(PromotionQuoteLineRequest::lineKey, value -> value));
        int linePosition = 1;
        for (PromotionQuoteLineResponse quoteLine : quote.lines()) {
            PromotionQuoteLineRequest requestedLine = requestsByLineKey.get(quoteLine.lineKey());
            BigDecimal paidUnit = positive(quoteLine.chargedBaseUnitPrice().add(quoteLine.configuration().unitAdjustmentTotal()));
            BigDecimal paidTotal = positive(paidUnit.multiply(BigDecimal.valueOf(quoteLine.quantity())));
            AppliedPromotionResponse promotion = quoteLine.appliedPromotion();
            OrderLineRecord paid = OrderLineRecord.createManualPaid(quoteLine.lineKey(), quoteLine.menuItemId(), linePosition++, quoteLine.name(),
                    quoteLine.quantity(), quoteLine.catalogBaseUnitPrice(), quoteLine.chargedBaseUnitPrice(),
                    quoteLine.configuration().unitAdjustmentTotal(), paidUnit, paidTotal,
                    promotion == null ? null : promotion.id(), promotion == null ? null : promotion.name(),
                    promotion == null ? null : promotion.benefitType().name(), requestedLine.note());
            snapshots(paid, quoteLine.configuration());
            componentOmissions(paid, quoteLine.menuItemId(), requestedLine.omittedComponentIds());
            order.addOrderLine(paid);
            for (PromotionRewardQuoteResponse reward : quoteLine.rewards()) {
                AppliedPromotionResponse rewardPromotion = reward.promotion();
                OrderLineRecord rewardLine = OrderLineRecord.createPromotionReward(paid, reward.menuItemId(), linePosition++, reward.name(),
                        reward.catalogBaseUnitPrice(), reward.configurationAdjustmentTotal(), nonNegative(reward.total()),
                        nonNegative(reward.total()), rewardPromotion.id(), rewardPromotion.name(),
                        rewardPromotion.benefitType().name(), reward.rewardOrdinal(), reward.note());
                snapshots(rewardLine, reward.configuration());
                componentOmissions(rewardLine, reward.omittedComponents());
                order.addOrderLine(rewardLine);
            }
        }
        for (NormalizedManualPricedLine manualLine : request.manualLines()) {
            order.addOrderLine(OrderLineRecord.createManualPricedLine(manualLine.lineKey(), linePosition++,
                    manualLine.description(), manualLine.quantity(), manualLine.unitAmount(), manualLine.lineTotal()));
        }
        OrderRecord saved = orderRepository.saveAndFlush(order);
        return ManualPosOrderReadService.response(saved, ManualOrderResult.CREATED);
    }

    private void componentOmissions(OrderLineRecord line, Long menuItemId, java.util.List<Long> componentIds) {
        for (MenuItemDefaultComponent component : menuItemComponentService
                .resolveActiveOmittedComponents(menuItemId, componentIds)) {
            line.addComponentOmissionSnapshot(OrderLineComponentOmissionSnapshot.create(component.getId(),
                    component.getComponentCode(), component.getDisplayName(), component.getDetail(),
                    component.getDisplayOrder()));
        }
    }

    private void componentOmissions(OrderLineRecord line,
                                    List<com.sushimei.sushimei.backend.catalog.DefaultComponentResponse> components) {
        for (com.sushimei.sushimei.backend.catalog.DefaultComponentResponse component : components) {
            line.addComponentOmissionSnapshot(OrderLineComponentOmissionSnapshot.create(component.id(), component.code(),
                    component.displayName(), component.detail(), component.displayOrder()));
        }
    }

    private OrderRecord createOrder(Long userId, NormalizedManualPosOrder request, PromotionQuoteResponse quote, BigDecimal total) {
        ParallelMoney money;
        try {
            money = parallelMoneyResolver.forWriteFromExact(total);
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
        order.setTransferReceiptPath(null);
        order.setPhoneNumber(null);
        order.setTotalAmountAmount(money.numericAmount());
        order.setTotalAmount(money.legacyAmount());
        order.setStatus("PREPARING");
        order.setCreatedAt(LocalDateTime.ofInstant(quote.quotedAt(), ZoneOffset.UTC));
        order.setOrderDetails(legacyDetails(quote, request.manualLines()));
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
                        selection.priceAdjustment(), selection.displayOnTicket());
                selectionCustomization(snapshot, selection);
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
                        selection.priceAdjustment(), selection.displayOnTicket());
                selectionCustomization(snapshot, selection);
                line.addSelectionSnapshot(snapshot);
                nestedSnapshots(line, snapshot, selection.groups());
            }
        }
    }

    private void selectionCustomization(OrderLineSelectionSnapshot snapshot, MenuQuoteSelectionResponse selection) {
        snapshot.setSelectionNote(selection.note());
        for (com.sushimei.sushimei.backend.catalog.DefaultComponentResponse component : selection.omittedComponents()) {
            snapshot.addComponentOmissionSnapshot(com.sushimei.sushimei.backend.entity.OrderLineSelectionComponentOmissionSnapshot
                    .create(component.id(), component.code(), component.displayName(), component.detail(), component.displayOrder()));
        }
    }

    private PromotionQuoteResponse emptyQuote(Instant now) {
        return new PromotionQuoteResponse(now, promotionQuoteService.businessTimeZone(), List.of(), zero(), zero(), zero(), zero());
    }

    private String legacyDetails(PromotionQuoteResponse quote, List<NormalizedManualPricedLine> manualLines) {
        String catalog = quote.lines().isEmpty() ? null : ManualPosOrderLegacyDetailsFormatter.format(quote);
        String manual = manualLines.stream().map(NormalizedManualPricedLine::description)
                .collect(java.util.stream.Collectors.joining("; "));
        if (catalog == null || catalog.isBlank()) return manual;
        return manual.isBlank() ? catalog : catalog + "; " + manual;
    }

    private BigDecimal zero() { return BigDecimal.ZERO.setScale(CheckoutMoney.SCALE); }

    private BigDecimal positive(BigDecimal value) {
        try { return checkoutMoney.normalizeNumericAmount(value); }
        catch (IllegalArgumentException exception) { throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception); }
    }

    private BigDecimal nonNegative(BigDecimal value) {
        try { return checkoutMoney.normalizeNonNegativeNumericAmount(value); }
        catch (IllegalArgumentException exception) { throw new ManualPosOrderException(ManualPosOrderError.ORDER_INVALID, exception); }
    }
}
