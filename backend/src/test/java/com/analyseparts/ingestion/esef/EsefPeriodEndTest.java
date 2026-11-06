package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Company;
import com.analyseparts.financials.ReportedFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.FilingInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsefPeriodEndTest {

    private static final Company HERMES = new Company("FR0000052292", "HERMES INTL", "RMS", "FR", "Euronext Paris");

    private static FilingInfo declared(String periodEnd) {
        return new FilingInfo("id", periodEnd, "FR", "/x.json", 0, null);
    }

    private static ReportedFact flow(LocalDate start, LocalDate end) {
        return new ReportedFact("ifrs-full:ProfitLoss", start, end, "iso4217:EUR", BigDecimal.ONE, -6);
    }

    @Test
    void factsWinWhenTheIndexDeclaresTheWrongYear() {
        var facts = List.of(flow(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
                flow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)));

        assertThat(EsefImportService.periodEnd(HERMES, declared("2026-12-31"), facts))
                .isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void oneDayGapKeepsTheDeclaredDate() {
        var facts = List.of(flow(LocalDate.of(2023, 4, 1), LocalDate.of(2024, 3, 30)));

        assertThat(EsefImportService.periodEnd(HERMES, declared("2024-03-31"), facts))
                .isEqualTo(LocalDate.of(2024, 3, 31));
    }
}
