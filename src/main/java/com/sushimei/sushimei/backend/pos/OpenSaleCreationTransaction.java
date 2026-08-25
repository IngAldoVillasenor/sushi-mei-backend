package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.security.AppUserRepository;
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
    private final BusinessDayService businessDayService;
    private final ParallelMoneyResolver parallelMoneyResolver;

    OpenSaleCreationTransaction(OrderRepository orderRepository,
                                AppUserRepository appUserRepository,
                                BusinessDayService businessDayService,
                                ParallelMoneyResolver parallelMoneyResolver) {
        this.orderRepository = orderRepository;
        this.appUserRepository = appUserRepository;
        this.businessDayService = businessDayService;
        this.parallelMoneyResolver = parallelMoneyResolver;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    OpenSaleResponse create(Long userId, NormalizedOpenSale request, Instant now) {
        OrderRecord existing = orderRepository.findByClientRequestIdWithOrderLines(request.requestId()).orElse(null);
        if (existing != null) {
            return existing(existing, userId, request.fingerprint());
        }
        appUserRepository.findById(userId).orElseThrow(() -> new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID));
        businessDayService.assertOpenBusinessDayForOpenSale(now);
        ParallelMoney money = parallelMoneyResolver.forWriteFromExact(request.amount());

        OrderRecord order = new OrderRecord();
        order.setClientRequestId(request.requestId());
        order.setCreatedByUserId(userId);
        order.setRequestFingerprint(request.fingerprint());
        order.setOrderSource(OrderSource.COUNTER);
        order.setPaymentMethod(request.paymentMethod());
        order.setCashDenomination(request.cashDenomination());
        order.setTotalAmountAmount(money.numericAmount());
        order.setTotalAmount(money.legacyAmount());
        order.setStatus("COMPLETED");
        order.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        order.setOrderDetails(request.description());
        order.addOrderLine(OrderLineRecord.createOpenSale(1, request.description(), request.amount()));
        return response(orderRepository.saveAndFlush(order), OpenSaleResult.CREATED);
    }

    static OpenSaleResponse existing(OrderRecord order, Long userId, String fingerprint) {
        if (order.getOrderSource() != OrderSource.COUNTER
                || !java.util.Objects.equals(order.getCreatedByUserId(), userId)
                || !java.util.Objects.equals(order.getRequestFingerprint(), fingerprint)
                || order.getOrderLines().stream().noneMatch(line -> line.getLineKind()
                == com.sushimei.sushimei.backend.entity.OrderLineKind.OPEN_SALE)) {
            throw new OpenSaleException(OpenSaleError.OPEN_SALE_IDEMPOTENCY_CONFLICT);
        }
        return response(order, OpenSaleResult.ALREADY_CREATED);
    }

    static OpenSaleResponse response(OrderRecord order, OpenSaleResult result) {
        OrderLineRecord line = order.getOrderLines().stream()
                .filter(candidate -> candidate.getLineKind() == com.sushimei.sushimei.backend.entity.OrderLineKind.OPEN_SALE)
                .findFirst().orElseThrow(() -> new OpenSaleException(OpenSaleError.OPEN_SALE_INVALID));
        return new OpenSaleResponse(order.getId(), order.getClientRequestId(), result, order.getOrderSource(),
                order.getCreatedByUserId(), line.getDishName(), line.getQuantity(), line.getUnitPriceAmount(),
                line.getLineTotalAmount(), order.getPaymentMethod(), order.getCashDenomination(), order.getStatus(),
                order.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
