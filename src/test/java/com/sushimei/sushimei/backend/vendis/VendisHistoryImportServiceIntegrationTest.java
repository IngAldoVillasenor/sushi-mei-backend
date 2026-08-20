package com.sushimei.sushimei.backend.vendis;

import com.sushimei.sushimei.backend.entity.OrderLineRecord;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.order.OrderLifecycleStatus;
import com.sushimei.sushimei.backend.orderread.OperationalOrderDetailResponse;
import com.sushimei.sushimei.backend.orderread.OperationalOrderReadService;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.repository.VendisOrderSnapshotRepository;
import com.sushimei.sushimei.backend.repository.VendisPaymentSnapshotRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        VendisHistoryImportServiceIntegrationTest.TestInfrastructureConfiguration.class})
class VendisHistoryImportServiceIntegrationTest {

    @Autowired
    private VendisHistoryImportService importService;

    @Autowired
    private VendisSaleMapper saleMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VendisOrderSnapshotRepository orderSnapshots;

    @Autowired
    private VendisPaymentSnapshotRepository paymentSnapshots;

    @Autowired
    private OperationalOrderReadService operationalOrderReadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanImportedEvidence() {
        jdbcTemplate.update("delete from public.vendis_payment_snapshots");
        jdbcTemplate.update("delete from public.vendis_order_snapshots");
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
    }

    @Test
    void importsAuditedVendisEvidenceWithoutOperationalCatalogOrPromotionResolution() throws Exception {
        VendisImportReport report = importService.importFile(resourcePath("vendis/audited-sales.ndjson"), false);

        assertThat(report.inputSales()).isEqualTo(9);
        assertThat(report.validSales()).isEqualTo(9);
        assertThat(report.imported()).isEqualTo(9);
        assertThat(report.alreadyExisting()).isZero();
        assertThat(report.completed()).isEqualTo(8);
        assertThat(report.voided()).isEqualTo(1);
        assertThat(report.lines()).isEqualTo(9);
        assertThat(report.payments()).isEqualTo(7);
        assertThat(report.zeroTotalSales()).isEqualTo(2);
        assertThat(report.zeroTotalLines()).isEqualTo(2);
        assertThat(report.missingExternalProductReferences()).isEqualTo(1);
        assertThat(report.globalDiscounts()).isEqualTo(3);
        assertThat(report.lineDiscounts()).isEqualTo(2);
        assertThat(report.historicalProjectionAdjustments()).isZero();
        assertThat(report.paymentReconciliationMismatches()).isEqualTo(1);
        assertThat(report.errors()).isZero();

        OrderRecord cash = imported("VEN-100");
        assertThat(cash.getOrderSource()).isEqualTo(OrderSource.VENDIS_IMPORT);
        assertThat(cash.getExternalReference()).isEqualTo("F-100");
        assertThat(cash.getStatus()).isEqualTo("COMPLETED");
        assertThat(cash.getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 8, 3, 18, 0));
        assertThat(cash.getPaymentMethod().name()).isEqualTo("CASH");
        assertThat(orderSnapshots.findById(cash.getId()).orElseThrow()).satisfies(snapshot -> {
            assertThat(snapshot.getDetailPaymentStatus()).isEqualTo("DETAIL_PAID");
            assertThat(snapshot.getSummaryPaymentStatusRaw()).isEqualTo("SUMMARY_PAID");
            assertThat(snapshot.getVendisStatus()).isEqualTo("closed");
            assertThat(snapshot.getCustomerName()).isEqualTo("Ana detalle");
            assertThat(snapshot.getContactId()).isEqualTo("C-1");
            assertThat(snapshot.getContactName()).isEqualTo("Ana resumen");
            assertThat(snapshot.getBusinessLocationName()).isEqualTo("Centro");
            assertThat(snapshot.getFinalTotalSource().toPlainString()).isEqualTo("79.0000");
            assertThat(snapshot.getSaleReconciliationDifference()).isEqualByComparingTo("0.0000");
            assertThat(snapshot.getPaymentReconciliationDifference()).isEqualByComparingTo("0.0000");
        });
        assertThat(cash.getTotalAmountAmount()).isEqualByComparingTo("79.00");

        OrderRecord card = imported("VEN-101");
        assertThat(card.getPaymentMethod().name()).isEqualTo("CARD");

        OrderRecord revoked = imported("VEN-102");
        assertThat(revoked.getStatus()).isEqualTo(OrderLifecycleStatus.VOIDED.persistedValue());
        assertThat(orderSnapshots.findById(revoked.getId()).orElseThrow().getPaymentReconciliationDifference())
                .isEqualByComparingTo("-79.0000");
        assertThat(operationalOrderReadService.activeOrders()).extracting(order -> order.id())
                .doesNotContain(revoked.getId());

        OrderRecord zero = imported("VEN-106");
        assertThat(zero.getTotalAmountAmount()).isEqualByComparingTo("0.00");
        assertThat(zero.getTotalAmount()).isEqualTo(0.0d);
        OrderLineRecord zeroLine = orderRepository.findOperationalDetailById(zero.getId()).orElseThrow().getOrderLines().get(0);
        assertThat(zeroLine.isExternalHistorical()).isTrue();
        assertThat(zeroLine.getUnitPriceAmount()).isEqualByComparingTo("0.00");
        assertThat(zeroLine.getLineTotalAmount()).isEqualByComparingTo("0.00");
        assertThat(zeroLine.getSourceDiscountPercentage()).isEqualByComparingTo("100.0000");

        OrderRecord defaultProduct = imported("VEN-107");
        OperationalOrderDetailResponse defaultProductDetail = operationalOrderReadService.order(defaultProduct.getId());
        assertThat(defaultProductDetail.total()).isEqualByComparingTo("0.00");
        assertThat(defaultProductDetail.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Default Product default-product");
            assertThat(line.externalProductReference()).isNull();
            assertThat(line.externalProductDetail()).isEqualTo("legacy placeholder");
            assertThat(line.externalHistorical()).isTrue();
            assertThat(line.sourceUnitPriceAmount()).isEqualByComparingTo("0.0000");
            assertThat(line.sourceLineTotalAmount()).isEqualByComparingTo("0.0000");
        });

        OrderRecord discounted = imported("VEN-105");
        OperationalOrderDetailResponse discountedDetail = operationalOrderReadService.order(discounted.getId());
        assertThat(discountedDetail.lines()).singleElement().satisfies(line -> {
            assertThat(line.sourceDiscountAmount()).isEqualByComparingTo("27.5000");
            assertThat(line.sourceDiscountPercentage()).isEqualByComparingTo("40.0000");
            assertThat(line.sourcePriceIncludingTaxAmount()).isEqualByComparingTo("100.0000");
            assertThat(line.promotion()).isNull();
        });
        assertThat(importedLine("VEN-105", 1)).satisfies(line -> {
            assertThat(line.getSourceUnitPriceAmount()).isEqualByComparingTo("100.0000");
            assertThat(line.getSourceLineTotalAmount()).isEqualByComparingTo("72.5000");
            assertThat(line.getUnitPriceAmount()).isEqualByComparingTo("72.50");
            assertThat(line.getLineTotalAmount()).isEqualByComparingTo("72.50");
        });
        assertThat(orderSnapshots.findById(imported("VEN-104").getId()).orElseThrow().getDiscountAmount())
                .isEqualByComparingTo("15.1250");
        assertThat(orderSnapshots.findById(imported("VEN-104").getId()).orElseThrow()).satisfies(snapshot -> {
            assertThat(snapshot.getSaleReconciliationDifference()).isEqualByComparingTo("-15.1200");
            assertThat(snapshot.getPaymentReconciliationDifference()).isEqualByComparingTo("0.0000");
        });
        assertThat(orderRepository.findOperationalDetailById(cash.getId()).orElseThrow().getOrderLines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.getSourceUnitPriceAmount().toPlainString()).isEqualTo("79.0000");
                    assertThat(line.getSourceLineTotalAmount().toPlainString()).isEqualTo("79.0000");
                    assertThat(line.getUnitPriceAmount()).isEqualByComparingTo("79.00");
                    assertThat(line.getLineTotalAmount()).isEqualByComparingTo("79.00");
                });
        assertThat(paymentSnapshots.findByOrderIdOrderByPositionAsc(imported("VEN-108").getId()))
                .extracting(payment -> payment.getAmount())
                .containsExactly(new BigDecimal("50.0000"), new BigDecimal("49.5000"));
        assertThat(imported("VEN-108").getPaymentMethod()).isNull();
        assertThat(orderSnapshots.findById(imported("VEN-108").getId()).orElseThrow()).satisfies(snapshot -> {
            assertThat(snapshot.getSaleReconciliationDifference()).isEqualByComparingTo("0.0000");
            assertThat(snapshot.getPaymentReconciliationDifference()).isEqualByComparingTo("-0.5000");
        });
    }

    @Test
    void reimportIsIdempotentAndDryRunWritesNothing() throws Exception {
        Path fixture = resourcePath("vendis/audited-sales.ndjson");
        VendisImportReport first = importService.importFile(fixture, false);
        VendisImportReport retry = importService.importFile(fixture, false);
        long persisted = orderRepository.count();
        VendisImportReport dryRun = importService.importFile(fixture, true);

        assertThat(first.imported()).isEqualTo(9);
        assertThat(retry.imported()).isZero();
        assertThat(retry.alreadyExisting()).isEqualTo(9);
        assertThat(dryRun.imported()).isZero();
        assertThat(dryRun.alreadyExisting()).isEqualTo(9);
        assertThat(orderRepository.count()).isEqualTo(persisted);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.vendis_order_snapshots", Long.class))
                .isEqualTo(9L);
    }

    @Test
    void dryRunAccumulatesUsefulMalformedLineDiagnosticsWithoutWrites() throws Exception {
        VendisImportReport report = importService.importFile(resourcePath("vendis/malformed-sales.ndjson"), true);

        assertThat(report.inputSales()).isEqualTo(1);
        assertThat(report.validSales()).isZero();
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.lineNumber()).isEqualTo(1);
            assertThat(diagnostic.reason()).contains("transactionDateRaw");
        });
        assertThat(orderRepository.count()).isZero();

        assertThatThrownBy(() -> importService.importFile(resourcePath("vendis/malformed-sales.ndjson"), false))
                .isInstanceOf(VendisImportException.class)
                .hasMessageContaining("input line 1");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void dryRunRejectsUnprojectableFourDecimalOperationalMoneyWithoutRoundingSourceEvidence() throws Exception {
        VendisImportReport report = importService.importFile(resourcePath("vendis/unprojectable-money.ndjson"), true);

        assertThat(report.inputSales()).isEqualTo(1);
        assertThat(report.validSales()).isZero();
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.vendisTransactionId()).isEqualTo("VEN-FOUR-DECIMAL");
            assertThat(diagnostic.reason()).contains("reconciliation fallback does not match");
        });
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void projectsHistoricalFinalChargedAmountsAndAppliesOnlyTheAuditedReconciliationFallback() throws Exception {
        VendisImportReport report = importService.importFile(
                resourcePath("vendis/historical-projection-compatible.ndjson"), false);

        assertThat(report.imported()).isEqualTo(3);
        assertThat(report.errors()).isZero();
        assertThat(report.historicalProjectionAdjustments()).isEqualTo(2);

        assertThat(importedLine("VEN-PROJECTION-9955509", 3)).satisfies(line -> {
            assertThat(line.getSourceUnitPriceAmount()).isEqualByComparingTo("30.0020");
            assertThat(line.getSourceLineTotalAmount()).isEqualByComparingTo("30.0020");
            assertThat(line.getUnitPriceAmount()).isEqualByComparingTo("30.00");
            assertThat(line.getLineTotalAmount()).isEqualByComparingTo("30.00");
            assertThat(line.getExternalProductReference()).isNull();
        });
        assertThat(imported("VEN-PROJECTION-9947937").getStatus())
                .isEqualTo(OrderLifecycleStatus.VOIDED.persistedValue());
        assertThat(importedLine("VEN-PROJECTION-9947937", 4)).satisfies(line -> {
            assertThat(line.getSourceLineTotalAmount()).isEqualByComparingTo("30.0020");
            assertThat(line.getLineTotalAmount()).isEqualByComparingTo("30.00");
        });
        assertThat(importedLine("VEN-PROJECTION-QUANTITY", 1)).satisfies(line -> {
            assertThat(line.getSourceUnitPriceAmount()).isEqualByComparingTo("55.0000");
            assertThat(line.getSourceLineTotalAmount()).isEqualByComparingTo("103.9600");
            assertThat(line.getLineTotalAmount()).isEqualByComparingTo("103.96");
            assertThat(line.getUnitPriceAmount()).isEqualByComparingTo("51.98");
        });
    }

    @Test
    void rejectsUnsafeSubCentHistoricalProjectionCasesWithoutRounding() throws Exception {
        VendisImportReport unsafe = importService.importFile(
                resourcePath("vendis/historical-projection-unsafe.ndjson"), true);

        assertThat(unsafe.inputSales()).isEqualTo(3);
        assertThat(unsafe.validSales()).isZero();
        assertThat(unsafe.errors()).isEqualTo(3);
        assertThat(unsafe.diagnostics()).extracting(diagnostic -> diagnostic.reason())
                .anyMatch(reason -> reason.contains("exactly one source line total"))
                .anyMatch(reason -> reason.contains("does not allow a global discount"))
                .anyMatch(reason -> reason.contains("does not match sale reconciliation difference"));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void mapsVendisWallTimeInAmericaMexicoCityToUtcAndKeepsOperationalLineRulesStrict() {
        assertThat(saleMapper.transactionInstant("08/20/2026 00:30"))
                .isEqualTo(Instant.parse("2026-08-20T06:30:00Z"));
        assertThatThrownBy(() -> OrderLineRecord.create(1L, 1, "Operational", 1,
                BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderLineRecord.create(1L, 1, "Operational", 2,
                new BigDecimal("10.00"), new BigDecimal("19.99")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseAllowsHistoricalLineRulesOnlyForVendisImportOrders() {
        Long vendisOrderId = insertSourceOrder(OrderSource.VENDIS_IMPORT, "VENDIS-SQL");
        jdbcTemplate.update("""
                insert into public.order_lines (
                    order_id, parent_order_source, external_historical, line_kind, line_position, dish_name,
                    quantity, unit_price_amount, line_total_amount, source_unit_price_amount, source_line_total_amount
                ) values (?, 'VENDIS_IMPORT', true, 'PAID', 1, 'Vendis zero line', 1, 0.00, 0.00, 0.0000, 0.0000)
                """, vendisOrderId);

        Long normalOrderId = insertSourceOrder(OrderSource.ANDROID_MANUAL, null);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.order_lines (
                    order_id, parent_order_source, external_historical, line_kind, line_position, dish_name,
                    quantity, unit_price_amount, line_total_amount, source_unit_price_amount, source_line_total_amount
                ) values (?, 'ANDROID_MANUAL', true, 'PAID', 1, 'Invalid bypass', 1, 0.00, 0.00, 0.0000, 0.0000)
                """, normalOrderId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.order_lines (
                    order_id, external_historical, line_kind, line_position, dish_name,
                    quantity, unit_price_amount, line_total_amount, source_unit_price_amount, source_line_total_amount
                ) values (?, true, 'PAID', 2, 'Null source bypass', 1, 0.00, 0.00, 0.0000, 0.0000)
                """, normalOrderId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void wrapsNonRacePersistenceFailureWithInputDiagnostics() throws Exception {
        OrderRepository repository = mock(OrderRepository.class);
        VendisHistoryImportTransaction transaction = mock(VendisHistoryImportTransaction.class);
        when(repository.findByOrderSourceAndExternalOrderId(eq(OrderSource.VENDIS_IMPORT), any()))
                .thenReturn(Optional.empty());
        when(transaction.importOne(any())).thenThrow(new DataIntegrityViolationException("database constraint"));
        VendisHistoryImportService isolatedService = new VendisHistoryImportService(
                new com.fasterxml.jackson.databind.ObjectMapper(), saleMapper, transaction, repository);

        assertThatThrownBy(() -> isolatedService.importFile(resourcePath("vendis/audited-sales.ndjson"), false))
                .isInstanceOf(VendisImportException.class)
                .hasMessageContaining("input line 1")
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    private OrderRecord imported(String transactionId) {
        return orderRepository.findByOrderSourceAndExternalOrderId(OrderSource.VENDIS_IMPORT, transactionId).orElseThrow();
    }

    private OrderLineRecord importedLine(String transactionId, int position) {
        return orderRepository.findOperationalDetailById(imported(transactionId).getId()).orElseThrow()
                .getOrderLines().stream()
                .filter(line -> line.getLinePosition() == position)
                .findFirst()
                .orElseThrow();
    }

    private Long insertSourceOrder(OrderSource source, String externalOrderId) {
        jdbcTemplate.update("""
                insert into public.orders (
                    order_source, external_order_id, total_amount, total_amount_amount, status, created_at
                ) values (?, ?, 10.00, 10.00, 'COMPLETED', current_timestamp)
                """, source.name(), externalOrderId);
        return jdbcTemplate.queryForObject("""
                select id from public.orders where order_source = ? and external_order_id is not distinct from ?
                order by id desc fetch first row only
                """, Long.class, source.name(), externalOrderId);
    }

    private Path resourcePath(String location) throws Exception {
        return new ClassPathResource(location).getFile().toPath();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
