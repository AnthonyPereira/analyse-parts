package com.analyseparts.financials;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "analyse-parts.ingestion.auto-lei=false")
@Import(TestcontainersConfiguration.class)
class FinancialsServiceIntegrationTest {

    @Autowired
    FinancialsService financials;

    @Autowired
    CompanyRepository companies;

    @Test
    void recordsFilingAndRebuildsHistoryFromDatabase() {
        Company company = companies.save(new Company("FR0000120271", "TOTALENERGIES", "TTE", "FR", "Euronext Paris"));
        LocalDate end = LocalDate.of(2025, 12, 31);
        financials.recordFiling(company.getId(),
                new FilingData("tte-2025-FR", end, "FR", "/tte.json", 0),
                List.of(new ReportedFact("ifrs-full:ProfitLoss", LocalDate.of(2025, 1, 1), end,
                                "iso4217:USD", new BigDecimal("13357000000.0"), -6),
                        new ReportedFact("ifrs-full:Assets", null, end,
                                "iso4217:USD", new BigDecimal("291055000000.0"), null)));

        FinancialHistory history = financials.history(company.getId());

        assertThat(financials.isImported("tte-2025-FR")).isTrue();
        assertThat(financials.hasFilings(company.getId())).isTrue();
        var year = history.years().getFirst();
        assertThat(year.fiscalYearEnd()).isEqualTo(end);
        assertThat(year.get(Metric.NET_INCOME)).get().extracting(FinancialHistory.Value::value, FinancialHistory.Value::currency)
                .containsExactly(new BigDecimal("13357000000.0"), "USD");
        assertThat(year.get(Metric.TOTAL_ASSETS)).isPresent();
    }
}
