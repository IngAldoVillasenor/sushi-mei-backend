package com.sushimei.sushimei.backend.vendis;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Maps the frozen Vendis export without consulting operational catalog or promotion state. */
@Component
public class VendisSaleMapper {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Mexico_City");
    private static final DateTimeFormatter TRANSACTION_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uuuu HH:mm");

    private final CheckoutMoney checkoutMoney;

    public VendisSaleMapper(CheckoutMoney checkoutMoney) {
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
    }

    MappedVendisSale map(VendisSaleInput source) {
        if (source == null) {
            throw new IllegalArgumentException("sale must not be null");
        }
        VendisSaleSummaryInput summary = requiredSummary(source.summary());
        String transactionId = requiredText(source.vendisTransactionId(), 120, "vendisTransactionId");
        String invoiceNumber = nullableText(source.effectiveInvoiceNumber(), 120, "invoiceNumber");
        LocalDateTime createdAtUtc = utcTransactionTime(source.effectiveTransactionDateRaw());
        int isRevocate = requiredRevocation(summary.isRevocate());

        BigDecimal sourceFinalTotal = requiredSourceAmount(summary.finalTotal(), "summary.finalTotal");
        BigDecimal projectedFinalTotal = projectToOperationalMoney(sourceFinalTotal, "summary.finalTotal");
        BigDecimal discountAmount = sourceAmount(summary.discountAmount(), "summary.discountAmount");
        List<MappedVendisLine> sourceLines = mapSourceLines(source.lines());
        List<MappedVendisPayment> payments = mapPayments(source.payments());
        BigDecimal computedLineSubtotal = sourceAmountOr(
                source.computedLineSubtotal(), sumSourceLineTotals(sourceLines), "computedLineSubtotal");
        BigDecimal computedPaymentsTotal = sourceAmountOr(
                source.computedPaymentsTotal(), sumPaymentAmounts(payments), "computedPaymentsTotal");
        BigDecimal saleReconciliationDifference = source.reconciliationDifference() == null
                ? sourceFinalTotal.subtract(computedLineSubtotal).setScale(4)
                : signedSourceAmount(source.reconciliationDifference(), "reconciliationDifference");
        BigDecimal paymentReconciliationDifference = computedPaymentsTotal.subtract(sourceFinalTotal).setScale(4);
        List<MappedVendisLine> lines = projectFinalChargedAmounts(
                sourceLines, sourceFinalTotal, discountAmount, saleReconciliationDifference);

        return new MappedVendisSale(
                transactionId,
                invoiceNumber,
                createdAtUtc,
                isRevocate,
                nullableText(source.detailPaymentStatus(), 120, "paymentStatus"),
                nullableText(summary.paymentStatusRaw(), 120, "summary.paymentStatusRaw"),
                nullableText(summary.status(), 120, "summary.status"),
                nullableText(source.customerName(), 255, "customerName"),
                sourceAmount(summary.totalBeforeTax(), "summary.totalBeforeTax"),
                sourceFinalTotal,
                projectedFinalTotal,
                discountAmount,
                nullableText(summary.discountType(), 32, "summary.discountType"),
                nullableText(summary.contactId(), 120, "summary.contactId"),
                nullableText(summary.contactName(), 255, "summary.contactName"),
                nullableText(summary.businessLocationName(), 255, "summary.businessLocationName"),
                sourceAmount(summary.totalPaid(), "summary.totalPaid"),
                sourceAmount(summary.totalDebt(), "summary.totalDebt"),
                computedLineSubtotal,
                computedPaymentsTotal,
                saleReconciliationDifference,
                paymentReconciliationDifference,
                genericPaymentMethod(payments),
                lines,
                payments);
    }

    /** Explicitly interprets Vendis wall time in the business zone, never the JVM default. */
    public Instant transactionInstant(String transactionDateRaw) {
        return utcTransactionTime(transactionDateRaw).toInstant(ZoneOffset.UTC);
    }

    private VendisSaleSummaryInput requiredSummary(VendisSaleSummaryInput summary) {
        if (summary == null) {
            throw new IllegalArgumentException("summary must be present");
        }
        return summary;
    }

    private int requiredRevocation(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("summary.isRevocate must be present");
        }
        return value;
    }

    private LocalDateTime utcTransactionTime(String value) {
        String raw = requiredText(value, 64, "transactionDateRaw");
        try {
            return LocalDateTime.parse(raw, TRANSACTION_DATE_FORMAT)
                    .atZone(BUSINESS_ZONE)
                    .toInstant()
                    .atOffset(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("transactionDateRaw must use MM/dd/yyyy HH:mm", exception);
        }
    }

    private List<MappedVendisLine> mapSourceLines(List<VendisLineInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        Set<Integer> positions = new HashSet<>();
        List<MappedVendisLine> mapped = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            VendisLineInput input = inputs.get(index);
            if (input == null) {
                throw new IllegalArgumentException("line " + (index + 1) + " must not be null");
            }
            int position = input.position() == null ? index + 1 : input.position();
            if (position <= 0 || !positions.add(position)) {
                throw new IllegalArgumentException("line positions must be positive and unique");
            }
            Integer quantity = input.quantity();
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("line quantity must be positive");
            }
            BigDecimal sourceUnitPrice = requiredSourceAmount(input.unitPrice(), "line.unitPrice");
            BigDecimal sourceLineTotal = requiredSourceAmount(input.lineTotal(), "line.lineTotal");
            mapped.add(new MappedVendisLine(
                    position,
                    requiredText(input.name(), 255, "line.name"),
                    nullableText(input.externalProductReference(), 120, "externalProductReference"),
                    nullableText(input.externalProductDetail(), 10_000, "externalProductDetail"),
                    quantity,
                    sourceUnitPrice,
                    null,
                    sourceAmount(input.discount(), "line.discount"),
                    sourceAmount(input.discountPercentage(), "line.discountPercentage"),
                    sourceAmount(input.tax(), "line.tax"),
                    sourceAmount(input.priceIncludingTax(), "line.priceIncludingTax"),
                    sourceLineTotal,
                    null));
        }
        return List.copyOf(mapped);
    }

    /**
     * Generic order-line money represents final charged evidence, while the
     * original Vendis unit price remains immutable SCALE=4 source evidence.
     */
    private List<MappedVendisLine> projectFinalChargedAmounts(
            List<MappedVendisLine> sourceLines,
            BigDecimal sourceFinalTotal,
            BigDecimal globalDiscountAmount,
            BigDecimal saleReconciliationDifference) {
        List<MappedVendisLine> nonCentSourceTotals = sourceLines.stream()
                .filter(line -> !isExactlyProjectable(line.sourceLineTotal()))
                .toList();
        if (nonCentSourceTotals.isEmpty()) {
            return sourceLines.stream().map(this::normalProjection).toList();
        }
        if (nonCentSourceTotals.size() != 1) {
            throw new IllegalArgumentException("Vendis historical reconciliation fallback requires exactly one "
                    + "source line total that cannot be projected exactly");
        }

        MappedVendisLine exceptionalLine = nonCentSourceTotals.get(0);
        if (globalDiscountAmount != null && globalDiscountAmount.signum() > 0) {
            throw new IllegalArgumentException("Vendis historical reconciliation fallback does not allow a global discount");
        }
        if (exceptionalLine.hasDiscount() || !isZeroOrAbsent(exceptionalLine.taxAmount())) {
            throw new IllegalArgumentException("Vendis historical reconciliation fallback requires an undiscounted zero-tax line");
        }

        List<MappedVendisLine> projected = new ArrayList<>();
        BigDecimal otherProjectedTotals = BigDecimal.ZERO.setScale(CheckoutMoney.SCALE);
        for (MappedVendisLine line : sourceLines) {
            if (line == exceptionalLine) {
                continue;
            }
            MappedVendisLine normal = normalProjection(line);
            projected.add(normal);
            otherProjectedTotals = otherProjectedTotals.add(normal.projectedLineTotal());
        }

        BigDecimal candidateProjectedLineTotal = sourceFinalTotal
                .subtract(otherProjectedTotals)
                .setScale(4, RoundingMode.UNNECESSARY);
        BigDecimal normalizedCandidate = projectToOperationalMoney(
                candidateProjectedLineTotal, "historical reconciliation candidate lineTotal");
        BigDecimal candidateUnitPrice = deriveProjectedUnitPrice(normalizedCandidate, exceptionalLine.quantity());
        BigDecimal adjustment = normalizedCandidate.setScale(4).subtract(exceptionalLine.sourceLineTotal());
        if (adjustment.compareTo(saleReconciliationDifference.setScale(4, RoundingMode.UNNECESSARY)) != 0) {
            throw new IllegalArgumentException("Vendis historical reconciliation fallback does not match sale reconciliation difference");
        }
        projected.add(withProjection(exceptionalLine, candidateUnitPrice, normalizedCandidate));
        return projected.stream().sorted(java.util.Comparator.comparingInt(MappedVendisLine::position)).toList();
    }

    private MappedVendisLine normalProjection(MappedVendisLine line) {
        BigDecimal projectedLineTotal = projectToOperationalMoney(line.sourceLineTotal(), "line.lineTotal");
        return withProjection(line, deriveProjectedUnitPrice(projectedLineTotal, line.quantity()), projectedLineTotal);
    }

    private MappedVendisLine withProjection(
            MappedVendisLine line, BigDecimal projectedUnitPrice, BigDecimal projectedLineTotal) {
        return new MappedVendisLine(
                line.position(),
                line.name(),
                line.externalProductReference(),
                line.externalProductDetail(),
                line.quantity(),
                line.sourceUnitPrice(),
                projectedUnitPrice,
                line.discountAmount(),
                line.discountPercentage(),
                line.taxAmount(),
                line.priceIncludingTaxAmount(),
                line.sourceLineTotal(),
                projectedLineTotal);
    }

    private BigDecimal deriveProjectedUnitPrice(BigDecimal projectedLineTotal, int quantity) {
        try {
            return projectedLineTotal.divide(BigDecimal.valueOf(quantity), CheckoutMoney.SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("line.lineTotal cannot produce an exact operational two-decimal unit amount", exception);
        }
    }

    private boolean isExactlyProjectable(BigDecimal amount) {
        try {
            projectToOperationalMoney(amount, "line.lineTotal");
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isZeroOrAbsent(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    private List<MappedVendisPayment> mapPayments(List<VendisPaymentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        Set<Integer> positions = new HashSet<>();
        List<MappedVendisPayment> mapped = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            VendisPaymentInput input = inputs.get(index);
            if (input == null) {
                throw new IllegalArgumentException("payment " + (index + 1) + " must not be null");
            }
            int position = input.position() == null ? index + 1 : input.position();
            if (position <= 0 || !positions.add(position)) {
                throw new IllegalArgumentException("payment positions must be positive and unique");
            }
            mapped.add(new MappedVendisPayment(
                    position,
                    nullableText(input.date(), 80, "payment.date"),
                    nullableText(input.reference(), 255, "payment.reference"),
                    requiredSourceAmount(input.amount(), "payment.amount"),
                    nullableText(input.method(), 120, "payment.method"),
                    nullableText(input.note(), 10_000, "payment.note")));
        }
        return List.copyOf(mapped);
    }

    private OrderPaymentMethod genericPaymentMethod(List<MappedVendisPayment> payments) {
        if (payments.isEmpty()) {
            return null;
        }
        Set<OrderPaymentMethod> methods = new HashSet<>();
        for (MappedVendisPayment payment : payments) {
            OrderPaymentMethod method = knownPaymentMethod(payment.method());
            if (method == null) {
                return null;
            }
            methods.add(method);
        }
        return methods.size() == 1 ? methods.iterator().next() : null;
    }

    private OrderPaymentMethod knownPaymentMethod(String rawMethod) {
        if (rawMethod == null) {
            return null;
        }
        String normalized = rawMethod.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CASH", "EFECTIVO" -> OrderPaymentMethod.CASH;
            case "CARD", "TARJETA", "CREDIT CARD", "DEBIT CARD", "TARJETA DE CREDITO", "TARJETA DE DÉBITO" ->
                    OrderPaymentMethod.CARD;
            case "TRANSFER", "TRANSFERENCIA" -> OrderPaymentMethod.TRANSFER;
            default -> null;
        };
    }

    private BigDecimal projectToOperationalMoney(BigDecimal sourceAmount, String name) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(sourceAmount);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name
                    + " cannot be projected exactly to operational two-decimal money", exception);
        }
    }

    private BigDecimal sourceAmountOr(BigDecimal supplied, BigDecimal calculated, String name) {
        return supplied == null ? calculated : sourceAmount(supplied, name);
    }

    private BigDecimal requiredSourceAmount(BigDecimal value, String name) {
        BigDecimal normalized = sourceAmount(value, name);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        return normalized;
    }

    private BigDecimal sourceAmount(BigDecimal value, String name) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException(name + " must be non-negative with at most four meaningful decimals");
        }
        BigDecimal normalized = value.setScale(4, RoundingMode.UNNECESSARY);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " exceeds source precision");
        }
        return normalized;
    }

    private BigDecimal signedSourceAmount(BigDecimal value, String name) {
        if (value == null || value.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException(name + " must have at most four meaningful decimals");
        }
        BigDecimal normalized = value.setScale(4, RoundingMode.UNNECESSARY);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " exceeds source precision");
        }
        return normalized;
    }

    private BigDecimal sumSourceLineTotals(List<MappedVendisLine> lines) {
        return lines.stream()
                .map(MappedVendisLine::sourceLineTotal)
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add)
                .setScale(4);
    }

    private BigDecimal sumPaymentAmounts(List<MappedVendisPayment> payments) {
        return payments.stream()
                .map(MappedVendisPayment::amount)
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add)
                .setScale(4);
    }

    private String requiredText(String value, int maximumLength, String name) {
        String normalized = nullableText(value, maximumLength, name);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
        return normalized;
    }

    private String nullableText(String value, int maximumLength, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds supported length");
        }
        return normalized;
    }
}
