package com.sushimei.sushimei.backend.vendis;

import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Disabled-by-default opt-in command-line boundary for a local exported NDJSON file. */
@Component
@Order(300)
class VendisHistoryImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(VendisHistoryImportRunner.class);
    private static final int MAX_LOGGED_DIAGNOSTICS = 20;

    private final VendisImportProperties properties;
    private final VendisHistoryImportService importService;

    VendisHistoryImportRunner(VendisImportProperties properties, VendisHistoryImportService importService) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.importService = Objects.requireNonNull(importService, "importService must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.enabled()) {
            VendisImportReport report = importService.importFile(Path.of(properties.file()), properties.dryRun());
            LOGGER.info("Vendis import completed dryRun={} inputSales={} validSales={} imported={} alreadyExisting={} "
                            + "completed={} voided={} lines={} payments={} zeroTotalSales={} zeroTotalLines={} "
                            + "missingExternalProductReferences={} globalDiscounts={} lineDiscounts={} "
                            + "historicalProjectionAdjustments={} paymentReconciliationMismatches={} errors={}",
                    properties.dryRun(), report.inputSales(), report.validSales(), report.imported(),
                    report.alreadyExisting(), report.completed(), report.voided(), report.lines(), report.payments(),
                    report.zeroTotalSales(), report.zeroTotalLines(), report.missingExternalProductReferences(),
                    report.globalDiscounts(), report.lineDiscounts(), report.historicalProjectionAdjustments(),
                    report.paymentReconciliationMismatches(),
                    report.errors());
            report.diagnostics().stream().limit(MAX_LOGGED_DIAGNOSTICS).forEach(diagnostic ->
                    LOGGER.warn("Vendis import diagnostic inputLine={} vendisTransactionId={} reason={}",
                            diagnostic.lineNumber(), diagnostic.vendisTransactionId(), diagnostic.reason()));
            if (report.diagnostics().size() > MAX_LOGGED_DIAGNOSTICS) {
                LOGGER.warn("Vendis import omitted {} additional diagnostics from logs",
                        report.diagnostics().size() - MAX_LOGGED_DIAGNOSTICS);
            }
        }
    }
}
