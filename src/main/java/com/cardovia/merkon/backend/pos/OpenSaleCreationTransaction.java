package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.businessday.BusinessDayService;
import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.checkout.ParallelMoney;
import com.cardovia.merkon.backend.checkout.ParallelMoneyResolver;
import com.cardovia.merkon.backend.entity.OrderLineRecord;
import com.cardovia.merkon.backend.entity.OrderRecord;
import com.cardovia.merkon.backend.entity.OrderSource;
import com.cardovia.merkon.backend.repository.OrderRepository;
import com.cardovia.merkon.backend.security.AppUserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/** Short, database-only transaction for an explicitly priced counter sale. */
@Service
class OpenSaleCreationTransaction {
    private final OrderRepository orderRepository;
    private final AppUserRepository appUserRepository;
    private final BusinessRepository businessRepository;
    private final BusinessDayService businessDayService;
    private final ParallelMoneyResolver parallelMoneyResolver;

    OpenSaleCreationTransaction(OrderRepository orderRepository,
                                AppUserRepository appUserRepository,
                                BusinessRepository businessRepository,
                                BusinessDayService businessDayService,
                                ParallelMoneyResolver parallelMoneyResolver) {
        this.orderRepository = orderRepository;
        this.appUserRepository = appUserRepository;
        this.businessRepository = businessRepository;
        this.businessDayService = businessDayService;
        this.parallelMoneyResolver = parallelMoneyResolver;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    OpenSaleResponse create(Long businessId, Long userId, NormalizedOpenSale request, Instant now) {
        OrderRecord existing = orderRepository.findByBusinessIdAndClientRequestIdWithOrderLines(businessId, request.requestId()).orElse(null);
        if (existing != null) {
            return existing(existing, userId, request.fingerprint());
        }
        appUserRepository.findById(userId).orElseThrow(() -> new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID));
        Business business = businessRepository.findByIdAndActiveTrue(businessId)
                .orElseThrow(() -> new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID));
        businessDayService.assertOpenBusinessDayForOpenSale(businessId, now);
        ParallelMoney money = parallelMoneyResolver.forWriteFromExact(request.amount());

        OrderRecord order = new OrderRecord();
        order.setBusiness(business);
        order.setClientRequestId(request.requestId());
        order.setCreatedByUserId(userId);
        order.setRequestFingerprint(request.fingerprint());
        order.setOrderSource(OrderSource.COUNTER);
        order.setPaymentMethod(request.paymentMethod());
        order.setCashDenomination(request.cashDenomination());
        order.setTotalAmountAmount(money.numericAmount());
        order.setTotalAmount(money.legacyAmount());
        order.setStatus("PREPARING");
        order.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        order.setOrderDetails(request.description());
        order.addOrderLine(OrderLineRecord.createOpenSale(1, request.description(), request.amount(),
                "open-" + request.requestId()));
        return response(orderRepository.saveAndFlush(order), OpenSaleResult.CREATED);
    }

    static OpenSaleResponse existing(OrderRecord order, Long userId, String fingerprint) {
        if (order.getOrderSource() != OrderSource.COUNTER
                || !java.util.Objects.equals(order.getCreatedByUserId(), userId)
                || !java.util.Objects.equals(order.getRequestFingerprint(), fingerprint)
                || order.getOrderLines().stream().noneMatch(line -> line.getLineKind()
                == com.cardovia.merkon.backend.entity.OrderLineKind.MANUAL_PRICED_LINE
                || line.getLineKind() == com.cardovia.merkon.backend.entity.OrderLineKind.OPEN_SALE)) {
            throw new OpenSaleException(OpenSaleError.OPEN_SALE_IDEMPOTENCY_CONFLICT);
        }
        return response(order, OpenSaleResult.ALREADY_CREATED);
    }

    static OpenSaleResponse response(OrderRecord order, OpenSaleResult result) {
        OrderLineRecord line = order.getOrderLines().stream()
                .filter(candidate -> candidate.getLineKind() == com.cardovia.merkon.backend.entity.OrderLineKind.MANUAL_PRICED_LINE
                        || candidate.getLineKind() == com.cardovia.merkon.backend.entity.OrderLineKind.OPEN_SALE)
                .findFirst().orElseThrow(() -> new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID));
        return new OpenSaleResponse(order.getId(), order.getClientRequestId(), result, order.getOrderSource(),
                order.getCreatedByUserId(), line.getDishName(), line.getQuantity(), line.getUnitPriceAmount(),
                line.getLineTotalAmount(), order.getPaymentMethod(), order.getCashDenomination(), order.getStatus(),
                order.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
