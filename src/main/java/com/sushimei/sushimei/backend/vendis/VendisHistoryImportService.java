package com.sushimei.sushimei.backend.vendis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Streaming local-file import boundary. It neither invokes operational order
 * commands nor looks at live catalog/promotion state.
 *
 * <p>Dry runs accumulate source diagnostics and perform no writes. Actual
 * imports stop on their first malformed record after independently committed
 * earlier records; source-key idempotency makes a corrected rerun safe.</p>
 */
@Service
public class VendisHistoryImportService {

    private final ObjectMapper objectMapper;
    private final VendisSaleMapper saleMapper;
    private final VendisHistoryImportTransaction importTransaction;
    private final OrderRepository orderRepository;

    public VendisHistoryImportService(ObjectMapper objectMapper,
                                      VendisSaleMapper saleMapper,
                                      VendisHistoryImportTransaction importTransaction,
                                      OrderRepository orderRepository) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.saleMapper = Objects.requireNonNull(saleMapper, "saleMapper must not be null");
        this.importTransaction = Objects.requireNonNull(importTransaction, "importTransaction must not be null");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
    }

    public VendisImportReport importFile(Path file, boolean dryRun) {
        if (file == null) {
            throw new IllegalArgumentException("Vendis import file must be provided");
        }
        ReportAccumulator report = new ReportAccumulator();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String raw;
            long lineNumber = 0;
            while ((raw = reader.readLine()) != null) {
                lineNumber++;
                if (raw.isBlank()) {
                    continue;
                }
                report.inputSales++;
                processLine(raw, lineNumber, dryRun, report);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read Vendis NDJSON import file", exception);
        }
        return report.build();
    }

    private void processLine(String raw, long lineNumber, boolean dryRun, ReportAccumulator report) {
        String transactionId = null;
        try {
            VendisSaleInput input = objectMapper.readValue(raw, VendisSaleInput.class);
            transactionId = input.vendisTransactionId();
            MappedVendisSale sale = saleMapper.map(input);
            report.recordValid(sale);

            boolean alreadyExists = orderRepository.findByOrderSourceAndExternalOrderId(
                    OrderSource.VENDIS_IMPORT, sale.transactionId()).isPresent();
            if (dryRun || alreadyExists) {
                if (alreadyExists) {
                    report.alreadyExisting++;
                }
                return;
            }

            try {
                VendisHistoryImportTransaction.ImportWriteResult result = importTransaction.importOne(sale);
                if (result == VendisHistoryImportTransaction.ImportWriteResult.IMPORTED) {
                    report.imported++;
                } else {
                    report.alreadyExisting++;
                }
            } catch (DataIntegrityViolationException exception) {
                // A concurrent import may win the unique source-identity race.
                if (orderRepository.findByOrderSourceAndExternalOrderId(OrderSource.VENDIS_IMPORT, sale.transactionId()).isPresent()) {
                    report.alreadyExisting++;
                } else {
                    throw persistenceFailure(lineNumber, transactionId, exception);
                }
            } catch (RuntimeException exception) {
                throw persistenceFailure(lineNumber, transactionId, exception);
            }
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            VendisImportDiagnostic diagnostic = new VendisImportDiagnostic(
                    lineNumber, safeTransactionId(transactionId), safeReason(exception));
            report.errors++;
            report.diagnostics.add(diagnostic);
            if (!dryRun) {
                throw new VendisImportException(diagnostic, exception);
            }
        }
    }

    private String safeTransactionId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeReason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Malformed Vendis source record" : message;
    }

    private VendisImportException persistenceFailure(long lineNumber, String transactionId, RuntimeException cause) {
        return new VendisImportException(new VendisImportDiagnostic(
                lineNumber,
                safeTransactionId(transactionId),
                "Unable to persist Vendis historical sale"), cause);
    }

    private static final class ReportAccumulator {
        private long inputSales;
        private long validSales;
        private long imported;
        private long alreadyExisting;
        private long voided;
        private long completed;
        private long lines;
        private long payments;
        private long zeroTotalSales;
        private long zeroTotalLines;
        private long missingExternalProductReferences;
        private long globalDiscounts;
        private long lineDiscounts;
        private long historicalProjectionAdjustments;
        private long paymentReconciliationMismatches;
        private long errors;
        private final List<VendisImportDiagnostic> diagnostics = new ArrayList<>();

        private void recordValid(MappedVendisSale sale) {
            validSales++;
            if (sale.voided()) {
                voided++;
            } else {
                completed++;
            }
            lines += sale.lines().size();
            payments += sale.payments().size();
            if (sale.zeroTotal()) {
                zeroTotalSales++;
            }
            zeroTotalLines += sale.lines().stream().filter(line -> line.sourceLineTotal().signum() == 0).count();
            missingExternalProductReferences += sale.lines().stream()
                    .filter(line -> line.externalProductReference() == null).count();
            if (sale.discountAmount() != null && sale.discountAmount().signum() > 0) {
                globalDiscounts++;
            }
            lineDiscounts += sale.lines().stream().filter(MappedVendisLine::hasDiscount).count();
            historicalProjectionAdjustments += sale.historicalProjectionAdjustments();
            if (sale.reconciliationMismatch()) {
                paymentReconciliationMismatches++;
            }
        }

        private VendisImportReport build() {
            return new VendisImportReport(inputSales, validSales, imported, alreadyExisting, voided, completed,
                    lines, payments, zeroTotalSales, zeroTotalLines, missingExternalProductReferences,
                    globalDiscounts, lineDiscounts, historicalProjectionAdjustments,
                    paymentReconciliationMismatches, errors, diagnostics);
        }
    }
}
