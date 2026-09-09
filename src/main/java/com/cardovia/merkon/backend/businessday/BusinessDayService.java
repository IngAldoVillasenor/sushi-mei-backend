package com.cardovia.merkon.backend.businessday;

import com.cardovia.merkon.backend.checkout.CheckoutMoney;
import com.cardovia.merkon.backend.checkout.ParallelMoneyResolver;
import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessRepository;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import com.cardovia.merkon.backend.entity.BusinessDay;
import com.cardovia.merkon.backend.entity.BusinessDayClosure;
import com.cardovia.merkon.backend.entity.BusinessDayOperationLock;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderRecord;
import com.cardovia.merkon.backend.entity.OrderSource;
import com.cardovia.merkon.backend.order.OrderLifecycleStatus;
import com.cardovia.merkon.backend.repository.BusinessDayOperationLockRepository;
import com.cardovia.merkon.backend.repository.BusinessDayCashExpenseRepository;
import com.cardovia.merkon.backend.repository.BusinessDayClosureRepository;
import com.cardovia.merkon.backend.repository.BusinessDayRepository;
import com.cardovia.merkon.backend.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative, database-only business-day opening and closing boundary.
 * It snapshots completed sales in the restaurant's local business-date interval.
 */
@Service
public class BusinessDayService {

    private final BusinessDayRepository businessDayRepository;
    private final BusinessRepository businessRepository;
    private final LegacyBusinessResolver legacyBusiness;
    private final BusinessDayClosureRepository businessDayClosureRepository;
    private final BusinessDayOperationLockRepository businessDayOperationLockRepository;
    private final BusinessDayCashExpenseRepository cashExpenseRepository;
    private final OrderRepository orderRepository;
    private final CheckoutMoney checkoutMoney;
    private final ParallelMoneyResolver parallelMoneyResolver;
    private final Clock clock;
    private final ZoneId businessZone;

    public BusinessDayService(BusinessDayRepository businessDayRepository,
                              BusinessRepository businessRepository,
                              LegacyBusinessResolver legacyBusiness,
                              BusinessDayClosureRepository businessDayClosureRepository,
                              BusinessDayOperationLockRepository businessDayOperationLockRepository,
                              BusinessDayCashExpenseRepository cashExpenseRepository,
                              OrderRepository orderRepository,
                              CheckoutMoney checkoutMoney,
                              ParallelMoneyResolver parallelMoneyResolver,
                              Clock clock,
                              @Value("${merkon.business-zone:America/Mexico_City}") String businessZone) {
        this.businessDayRepository = Objects.requireNonNull(businessDayRepository, "businessDayRepository must not be null");
        this.businessRepository = Objects.requireNonNull(businessRepository, "businessRepository must not be null");
        this.legacyBusiness = Objects.requireNonNull(legacyBusiness, "legacyBusiness must not be null");
        this.businessDayClosureRepository = Objects.requireNonNull(businessDayClosureRepository,
                "businessDayClosureRepository must not be null");
        this.businessDayOperationLockRepository = Objects.requireNonNull(businessDayOperationLockRepository,
                "businessDayOperationLockRepository must not be null");
        this.cashExpenseRepository = Objects.requireNonNull(cashExpenseRepository,
                "cashExpenseRepository must not be null");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
        this.parallelMoneyResolver = Objects.requireNonNull(parallelMoneyResolver,
                "parallelMoneyResolver must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.businessZone = ZoneId.of(Objects.requireNonNull(businessZone, "businessZone must not be null"));
    }

    @Transactional
    public BusinessDayResponse open(Long openedByUserId, OpenBusinessDayRequest request) {
        return open(legacyBusiness.requireLegacyBusinessId(), openedByUserId, request);
    }

    @Transactional
    public BusinessDayResponse open(Long businessId, Long openedByUserId, OpenBusinessDayRequest request) {
        requireActor(openedByUserId);
        Business business = requireBusiness(businessId);
        BigDecimal openingCashAmount = nonNegative(request == null ? null : request.openingCashAmount());
        Instant now = clock.instant();
        LocalDate businessDate = now.atZone(businessZone).toLocalDate();

        lockCurrentDayOperations(businessId);
        if (businessDayRepository.findOpenForUpdate(businessId).isPresent()) {
            throw failure(BusinessDayError.BUSINESS_DAY_ALREADY_OPEN);
        }
        Optional<BusinessDay> existingForDate = businessDayRepository.findByBusinessIdAndBusinessDate(businessId, businessDate);
        if (existingForDate.isPresent()) {
            throw failure(existingForDate.orElseThrow().getStatus() == BusinessDayStatus.OPEN
                    ? BusinessDayError.BUSINESS_DAY_ALREADY_OPEN
                    : BusinessDayError.BUSINESS_DAY_ALREADY_CLOSED);
        }

        try {
            BusinessDay saved = businessDayRepository.saveAndFlush(
                    BusinessDay.open(business, businessDate, openingCashAmount, now, openedByUserId));
            return BusinessDayResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            // The unique open_guard / business_date constraints are the concurrency backstop.
            throw failure(BusinessDayError.BUSINESS_DAY_ALREADY_OPEN, exception);
        }
    }

    @Transactional(readOnly = true)
    public Optional<BusinessDayResponse> current() {
        return current(legacyBusiness.requireLegacyBusinessId());
    }

    @Transactional(readOnly = true)
    public Optional<BusinessDayResponse> current(Long businessId) {
        Optional<BusinessDay> openBusinessDay = businessDayRepository.findByBusinessIdAndStatus(businessId, BusinessDayStatus.OPEN);
        if (openBusinessDay.isPresent()) {
            return openBusinessDay.map(BusinessDayResponse::from);
        }
        LocalDate today = clock.instant().atZone(businessZone).toLocalDate();
        return businessDayRepository.findByBusinessIdAndBusinessDate(businessId, today).map(this::currentResponse);
    }

    @Transactional(readOnly = true)
    public boolean hasOpenBusinessDay() {
        return hasOpenBusinessDay(legacyBusiness.requireLegacyBusinessId());
    }

    @Transactional(readOnly = true)
    public boolean hasOpenBusinessDay(Long businessId) {
        return businessDayRepository.findByBusinessIdAndStatus(businessId, BusinessDayStatus.OPEN).isPresent();
    }

    /**
     * Returns the persisted state for today's local business date without
     * collapsing a missing day into an OPEN/CLOSED answer.
     */
    @Transactional(readOnly = true)
    public Optional<BusinessDayStatus> currentBusinessDayStatus() {
        return currentBusinessDayStatus(legacyBusiness.requireLegacyBusinessId());
    }

    @Transactional(readOnly = true)
    public Optional<BusinessDayStatus> currentBusinessDayStatus(Long businessId) {
        LocalDate businessDate = clock.instant().atZone(businessZone).toLocalDate();
        return businessDayRepository.findByBusinessIdAndBusinessDate(businessId, businessDate).map(BusinessDay::getStatus);
    }

    /**
     * Prevents a new physical sale from committing after its local business
     * date has already been closed. It deliberately does not gate WhatsApp
     * ordering; that policy belongs to Phase 8F3.
     *
     * <p>The singleton lock is also held by {@link #close(Long,
     * CloseBusinessDayRequest)}. When this method joins an order-creation
     * transaction, either the order commits before the close snapshot, or the
     * close commits first and this method rejects the order.</p>
     */
    @Transactional
    public void assertPhysicalOrderCreationAllowed(OrderSource orderSource, Instant createdAt) {
        assertPhysicalOrderCreationAllowed(legacyBusiness.requireLegacyBusinessId(), orderSource, createdAt);
    }

    @Transactional
    public void assertPhysicalOrderCreationAllowed(Long businessId, OrderSource orderSource, Instant createdAt) {
        if (orderSource != OrderSource.ANDROID_MANUAL && orderSource != OrderSource.COUNTER) {
            return;
        }
        if (createdAt == null) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }

        lockCurrentDayOperations(businessId);
        LocalDate businessDate = createdAt.atZone(businessZone).toLocalDate();
        if (businessDayRepository.findByBusinessIdAndBusinessDate(businessId, businessDate)
                .map(BusinessDay::getStatus)
                .orElse(null) == BusinessDayStatus.CLOSED) {
            throw failure(BusinessDayError.BUSINESS_DAY_CLOSED);
        }
    }

    /**
     * Open Sales are immediate counter revenue and therefore require an open
     * drawer for their own local business date. This is intentionally stricter
     * than normal catalog ordering, which may precede a forgotten opening.
     */
    @Transactional
    public void assertOpenBusinessDayForOpenSale(Instant createdAt) {
        assertOpenBusinessDayForOpenSale(legacyBusiness.requireLegacyBusinessId(), createdAt);
    }

    @Transactional
    public void assertOpenBusinessDayForOpenSale(Long businessId, Instant createdAt) {
        assertOpenBusinessDayForOperationalSettlement(businessId, createdAt);
    }

    /**
     * Requires an OPEN business day for an operational settlement that changes an order into a completed sale.
     * It shares the singleton operation lock with close so settlement cannot escape the closing snapshot.
     */
    @Transactional
    public void assertOpenBusinessDayForOperationalSettlement(Instant createdAt) {
        assertOpenBusinessDayForOperationalSettlement(legacyBusiness.requireLegacyBusinessId(), createdAt);
    }

    @Transactional
    public void assertOpenBusinessDayForOperationalSettlement(Long businessId, Instant createdAt) {
        if (createdAt == null) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }
        lockCurrentDayOperations(businessId);
        LocalDate businessDate = createdAt.atZone(businessZone).toLocalDate();
        BusinessDayStatus status = businessDayRepository.findByBusinessIdAndBusinessDate(businessId, businessDate)
                .map(BusinessDay::getStatus)
                .orElse(null);
        if (status != BusinessDayStatus.OPEN) {
            throw failure(BusinessDayError.BUSINESS_DAY_OPEN_REQUIRED);
        }
    }

    @Transactional
    public BusinessDayResponse close(Long closedByUserId, CloseBusinessDayRequest request) {
        return close(legacyBusiness.requireLegacyBusinessId(), closedByUserId, request);
    }

    @Transactional
    public BusinessDayResponse close(Long businessId, Long closedByUserId, CloseBusinessDayRequest request) {
        requireActor(closedByUserId);
        BigDecimal actualClosingCashAmount = nonNegative(request == null ? null : request.actualClosingCashAmount());
        lockCurrentDayOperations(businessId);
        BusinessDay businessDay = businessDayRepository.findOpenForUpdate(businessId)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_NOT_OPEN));
        rejectIfNonTerminalOrdersExist(businessId, businessDay.getBusinessDate());
        SalesSnapshot sales = completedSalesFor(businessId, businessDay.getBusinessDate());
        CashExpenseSnapshot expenses = cashExpensesFor(businessDay.getId());
        BigDecimal expectedClosingCashAmount = subtractNonNegative(
                add(sales.cashSalesAmount(), businessDay.getOpeningCashAmount()), expenses.cashExpenseAmount());
        BigDecimal cashDifferenceAmount = signed(actualClosingCashAmount.subtract(expectedClosingCashAmount));

        businessDay.close(
                sales.completedSalesAmount(),
                sales.cashSalesAmount(),
                expenses.cashExpenseAmount(),
                expenses.cashExpenseCount(),
                sales.transferSalesAmount(),
                sales.cardSalesAmount(),
                sales.unclassifiedSalesAmount(),
                sales.completedOrderCount(),
                sales.voidedOrderCount(),
                expectedClosingCashAmount,
                actualClosingCashAmount,
                cashDifferenceAmount,
                clock.instant(),
                closedByUserId);
        int closeNumber = businessDayClosureRepository.findTopByBusinessDayIdOrderByCloseNumberDesc(businessDay.getId())
                .map(existing -> existing.getCloseNumber() + 1)
                .orElse(1);
        BusinessDayClosure closure = businessDayClosureRepository.saveAndFlush(
                BusinessDayClosure.from(businessDay, closeNumber));
        return BusinessDayResponse.fromClosed(businessDay, closure);
    }

    /**
     * Continues today's already closed business day without changing its
     * original opening cash. The prior close remains append-only evidence in
     * {@code business_day_closures}; only the mutable current snapshot is
     * cleared while the day is operationally reopened.
     */
    @Transactional
    public BusinessDayResponse reopen(Long reopenedByUserId) {
        return reopen(legacyBusiness.requireLegacyBusinessId(), reopenedByUserId);
    }

    @Transactional
    public BusinessDayResponse reopen(Long businessId, Long reopenedByUserId) {
        requireActor(reopenedByUserId);
        Instant now = clock.instant();
        LocalDate today = now.atZone(businessZone).toLocalDate();

        lockCurrentDayOperations(businessId);
        BusinessDay businessDay = businessDayRepository.findByBusinessIdAndBusinessDateForUpdate(businessId, today)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_REOPEN_NOT_ALLOWED));
        if (businessDay.getStatus() != BusinessDayStatus.CLOSED) {
            throw failure(BusinessDayError.BUSINESS_DAY_NOT_CLOSED);
        }
        if (businessDayRepository.findOpenForUpdate(businessId).isPresent()) {
            throw failure(BusinessDayError.BUSINESS_DAY_REOPEN_NOT_ALLOWED);
        }
        if (!businessDayClosureRepository.existsByBusinessDayId(businessDay.getId())) {
            throw failure(BusinessDayError.BUSINESS_DAY_REOPEN_NOT_ALLOWED);
        }

        businessDay.reopen(now, reopenedByUserId);
        businessDayRepository.flush();
        return BusinessDayResponse.from(businessDay);
    }

    private SalesSnapshot completedSalesFor(Long businessId, LocalDate businessDate) {
        BusinessDateInterval interval = intervalFor(businessDate);
        BigDecimal cash = zero();
        BigDecimal transfer = zero();
        BigDecimal card = zero();
        BigDecimal unclassified = zero();
        List<OrderRecord> completed = orderRepository.findCompletedForBusinessDateForBusiness(businessId, interval.from(), interval.to());
        for (OrderRecord order : completed) {
            BigDecimal total = total(order);
            if (order.getPaymentMethod() == OrderPaymentMethod.CASH) {
                cash = add(cash, total);
            } else if (order.getPaymentMethod() == OrderPaymentMethod.TRANSFER) {
                transfer = add(transfer, total);
            } else if (order.getPaymentMethod() == OrderPaymentMethod.CARD) {
                card = add(card, total);
            } else {
                unclassified = add(unclassified, total);
            }
        }
        BigDecimal completedSales = add(add(cash, transfer), add(card, unclassified));
        long voided = orderRepository.countVoidedOrdersForBusiness(businessId, interval.from(), interval.to());
        return new SalesSnapshot(completedSales, cash, transfer, card, unclassified, completed.size(), voided);
    }

    private CashExpenseSnapshot cashExpensesFor(Long businessDayId) {
        try {
            BigDecimal amount = cashExpenseRepository.sumAmountByBusinessDayId(businessDayId);
            return new CashExpenseSnapshot(checkoutMoney.normalizeNonNegativeNumericAmount(amount),
                    cashExpenseRepository.countByBusinessDayId(businessDayId));
        } catch (IllegalArgumentException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private BusinessDayResponse currentResponse(BusinessDay businessDay) {
        if (businessDay.getStatus() != BusinessDayStatus.CLOSED) {
            return BusinessDayResponse.from(businessDay);
        }
        BusinessDayClosure closure = businessDayClosureRepository
                .findTopByBusinessDayIdOrderByCloseNumberDesc(businessDay.getId())
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));
        return BusinessDayResponse.fromClosed(businessDay, closure);
    }

    private void rejectIfNonTerminalOrdersExist(Long businessId, LocalDate businessDate) {
        BusinessDateInterval interval = intervalFor(businessDate);
        long nonTerminalOrders = orderRepository.countNonTerminalForBusinessDateForBusiness(
                businessId, interval.from(), interval.to(), OrderLifecycleStatus.terminalPersistedValues());
        if (nonTerminalOrders > 0) {
            throw failure(BusinessDayError.BUSINESS_DAY_HAS_ACTIVE_ORDERS);
        }
    }

    private BusinessDateInterval intervalFor(LocalDate businessDate) {
        LocalDateTime from = businessDate.atStartOfDay(businessZone).toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = businessDate.plusDays(1).atStartOfDay(businessZone).toInstant().atOffset(ZoneOffset.UTC)
                .toLocalDateTime();
        return new BusinessDateInterval(from, to);
    }

    private BigDecimal total(OrderRecord order) {
        try {
            return order.getOrderSource() == OrderSource.VENDIS_IMPORT
                    ? parallelMoneyResolver.resolveExternalHistorical(order.getTotalAmountAmount(), order.getTotalAmount())
                    : parallelMoneyResolver.resolve(order.getTotalAmountAmount(), order.getTotalAmount());
        } catch (RuntimeException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private void lockCurrentDayOperations(Long businessId) {
        // The business row serializes first-operation lock-row creation per tenant without
        // imposing contention on an unrelated business.
        businessRepository.findByIdForBusinessDayOperations(businessId)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));
        if (!businessDayOperationLockRepository.existsById(businessId)) {
            businessDayOperationLockRepository.saveAndFlush(BusinessDayOperationLock.forBusiness(businessId));
        }
        businessDayOperationLockRepository.findByBusinessIdForUpdate(businessId)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));
    }

    private Business requireBusiness(Long businessId) {
        return businessRepository.findById(businessId).filter(Business::isActive)
                .orElseThrow(() -> failure(BusinessDayError.BUSINESS_DAY_INVALID));
    }

    private BigDecimal nonNegative(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(left.add(right));
        } catch (IllegalArgumentException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private BigDecimal signed(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeSignedNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private BigDecimal subtractNonNegative(BigDecimal availableCash, BigDecimal cashExpenses) {
        if (availableCash.compareTo(cashExpenses) < 0) {
            throw failure(BusinessDayError.BUSINESS_DAY_CASH_EXPENSES_EXCEED_AVAILABLE_CASH);
        }
        try {
            return checkoutMoney.normalizeNonNegativeNumericAmount(availableCash.subtract(cashExpenses));
        } catch (IllegalArgumentException exception) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID, exception);
        }
    }

    private static void requireActor(Long userId) {
        if (userId == null || userId <= 0) {
            throw failure(BusinessDayError.BUSINESS_DAY_INVALID);
        }
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(CheckoutMoney.SCALE);
    }

    private static BusinessDayException failure(BusinessDayError error) {
        return new BusinessDayException(error);
    }

    private static BusinessDayException failure(BusinessDayError error, Throwable cause) {
        return new BusinessDayException(error, cause);
    }

    private record SalesSnapshot(BigDecimal completedSalesAmount,
                                 BigDecimal cashSalesAmount,
                                 BigDecimal transferSalesAmount,
                                 BigDecimal cardSalesAmount,
                                 BigDecimal unclassifiedSalesAmount,
                                 long completedOrderCount,
                                 long voidedOrderCount) {
    }

    private record CashExpenseSnapshot(BigDecimal cashExpenseAmount, long cashExpenseCount) {
    }

    private record BusinessDateInterval(LocalDateTime from, LocalDateTime to) {
    }
}
