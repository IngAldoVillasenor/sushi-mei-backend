package com.sushimei.sushimei.backend.vendis;

import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;
import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.entity.VendisOrderSnapshot;
import com.sushimei.sushimei.backend.entity.VendisPaymentSnapshot;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.repository.VendisOrderSnapshotRepository;
import com.sushimei.sushimei.backend.repository.VendisPaymentSnapshotRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One short, isolated database transaction per valid Vendis sale. */
@Component
class VendisHistoryImportTransaction {

    private final OrderRepository orderRepository;
    private final VendisOrderSnapshotRepository orderSnapshots;
    private final VendisPaymentSnapshotRepository paymentSnapshots;
    private final ParallelMoneyResolver parallelMoneyResolver;

    VendisHistoryImportTransaction(OrderRepository orderRepository,
                                   VendisOrderSnapshotRepository orderSnapshots,
                                   VendisPaymentSnapshotRepository paymentSnapshots,
                                   ParallelMoneyResolver parallelMoneyResolver) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.orderSnapshots = Objects.requireNonNull(orderSnapshots, "orderSnapshots must not be null");
        this.paymentSnapshots = Objects.requireNonNull(paymentSnapshots, "paymentSnapshots must not be null");
        this.parallelMoneyResolver = Objects.requireNonNull(parallelMoneyResolver,
                "parallelMoneyResolver must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ImportWriteResult importOne(MappedVendisSale sale) {
        if (orderRepository.findByOrderSourceAndExternalOrderId(OrderSource.VENDIS_IMPORT, sale.transactionId()).isPresent()) {
            return ImportWriteResult.ALREADY_EXISTS;
        }

        ParallelMoney total = parallelMoneyResolver.forWriteFromExternalHistorical(sale.projectedFinalTotal());
        OrderRecord order = new OrderRecord();
        order.setOrderSource(OrderSource.VENDIS_IMPORT);
        order.setExternalOrderId(sale.transactionId());
        order.setExternalReference(sale.invoiceNumber());
        order.setStatus(sale.voided()
                ? OrderLifecycleStatus.VOIDED.persistedValue()
                : OrderLifecycleStatus.COMPLETED.persistedValue());
        order.setCreatedAt(sale.createdAtUtc());
        order.setPaymentMethod(sale.genericPaymentMethod());
        order.setTotalAmountAmount(total.numericAmount());
        order.setTotalAmount(total.legacyAmount());
        for (MappedVendisLine line : sale.lines()) {
            order.addOrderLine(OrderLineRecord.createExternalHistoricalPaid(
                    line.externalProductReference(), line.externalProductDetail(), line.position(), line.name(),
                    line.quantity(), line.sourceUnitPrice(), line.sourceLineTotal(), line.projectedUnitPrice(),
                    line.projectedLineTotal(), line.discountAmount(), line.discountPercentage(), line.taxAmount(),
                    line.priceIncludingTaxAmount()));
        }

        OrderRecord saved = orderRepository.saveAndFlush(order);
        orderSnapshots.save(VendisOrderSnapshot.create(
                saved,
                sale.detailPaymentStatus(),
                sale.summaryPaymentStatusRaw(),
                sale.vendisStatus(),
                sale.customerName(),
                sale.totalBeforeTax(),
                sale.sourceFinalTotal(),
                sale.discountAmount(),
                sale.discountType(),
                sale.isRevocate(),
                sale.contactId(),
                sale.contactName(),
                sale.businessLocationName(),
                sale.totalPaid(),
                sale.totalDebt(),
                sale.computedLineSubtotal(),
                sale.computedPaymentsTotal(),
                sale.saleReconciliationDifference(),
                sale.paymentReconciliationDifference()));
        for (MappedVendisPayment payment : sale.payments()) {
            paymentSnapshots.save(VendisPaymentSnapshot.create(
                    saved, payment.position(), payment.dateRaw(), payment.reference(), payment.amount(),
                    payment.method(), payment.note()));
        }
        return ImportWriteResult.IMPORTED;
    }

    enum ImportWriteResult {
        IMPORTED,
        ALREADY_EXISTS
    }
}
