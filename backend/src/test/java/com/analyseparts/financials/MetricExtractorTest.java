package com.analyseparts.financials;

import com.analyseparts.financials.MetricExtractor.SourcedFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricExtractorTest {

    private static final LocalDate END_2024 = LocalDate.of(2024, 12, 31);
    private static final LocalDate END_2025 = LocalDate.of(2025, 12, 31);

    private static SourcedFact flow(String concept, int year, String value, String unit, String filing, LocalDate filingEnd) {
        return new SourcedFact(new ReportedFact(concept, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                unit, new BigDecimal(value), -6), filing, filingEnd);
    }

    private static SourcedFact stock(String concept, LocalDate date, String value, String filing, LocalDate filingEnd) {
        return new SourcedFact(new ReportedFact(concept, null, date, "iso4217:EUR", new BigDecimal(value), -6),
                filing, filingEnd);
    }

    @Test
    void readsCurrentAndComparativeYearsFromOneFiling() {
        var history = MetricExtractor.extract(List.of(
                flow("ifrs-full:ProfitLoss", 2025, "100", "iso4217:EUR", "R2025", END_2025),
                flow("ifrs-full:ProfitLoss", 2024, "90", "iso4217:EUR", "R2025", END_2025),
                stock("ifrs-full:Assets", END_2025, "1000", "R2025", END_2025)));

        assertThat(history.years()).extracting(FinancialHistory.Year::fiscalYearEnd).containsExactly(END_2025, END_2024);
        assertThat(history.years().get(0).get(Metric.NET_INCOME)).get()
                .extracting(FinancialHistory.Value::value).isEqualTo(new BigDecimal("100"));
        assertThat(history.years().get(1).get(Metric.NET_INCOME)).get()
                .extracting(FinancialHistory.Value::value).isEqualTo(new BigDecimal("90"));
    }

    @Test
    void newestFilingWinsForRestatedFigures() {
        var history = MetricExtractor.extract(List.of(
                flow("ifrs-full:ProfitLoss", 2024, "90", "iso4217:EUR", "R2024", END_2024),
                flow("ifrs-full:ProfitLoss", 2024, "88", "iso4217:EUR", "R2025", END_2025))); // retraité

        var y2024 = history.years().stream().filter(y -> y.fiscalYearEnd().equals(END_2024)).findFirst().orElseThrow();
        assertThat(y2024.get(Metric.NET_INCOME)).get()
                .extracting(FinancialHistory.Value::value, FinancialHistory.Value::filing)
                .containsExactly(new BigDecimal("88"), "R2025");
    }

    @Test
    void triesCandidateConceptsInOrder() {
        var history = MetricExtractor.extract(List.of(
                flow("ifrs-full:RevenueFromContractsWithCustomers", 2025, "500", "iso4217:EUR", "R", END_2025)));

        assertThat(history.years().getFirst().get(Metric.REVENUE)).get()
                .extracting(FinancialHistory.Value::concept).isEqualTo("ifrs-full:RevenueFromContractsWithCustomers");
    }

    @Test
    void checksPeriodLengthAndUnit() {
        var halfYear = new SourcedFact(new ReportedFact("ifrs-full:Revenue", LocalDate.of(2025, 7, 1), END_2025,
                "iso4217:EUR", new BigDecimal("250"), -6), "R", END_2025);
        var history = MetricExtractor.extract(List.of(
                halfYear,
                flow("ifrs-full:BasicEarningsLossPerShare", 2025, "4.62", "iso4217:USD/xbrli:shares", "R", END_2025)));

        var year = history.years().getFirst();
        assertThat(year.get(Metric.REVENUE)).isEmpty();                    // 6 mois : pas un exercice
        assertThat(year.get(Metric.EPS_BASIC)).get()
                .extracting(FinancialHistory.Value::currency).isEqualTo("USD"); // devise du BPA
        assertThat(year.get(Metric.NET_INCOME)).isEmpty();
    }

    @Test
    void bankLinesAreFoundInTheBankOwnExtension() {
        // Société Générale : PNB et RBE dans son extension « SocGen: », pas dans la taxonomie IFRS
        var history = MetricExtractor.extract(List.of(
                flow("SocGen:NetBankingIncome", 2025, "27254", "iso4217:EUR", "R", END_2025),
                flow("SocGen:GrossOperatingIncome", 2025, "9916", "iso4217:EUR", "R", END_2025),
                flow("SocGen:NetBankingIncomeOfFrenchRetail", 2025, "1", "iso4217:EUR", "R", END_2025),
                flow("ifrs-full:DividendsPaid", 2025, "2035", "iso4217:EUR", "R", END_2025)));

        var year = history.years().getFirst();
        assertThat(year.get(Metric.NET_BANKING_INCOME)).get()
                .extracting(FinancialHistory.Value::value, FinancialHistory.Value::concept)
                .containsExactly(new BigDecimal("27254"), "SocGen:NetBankingIncome"); // le vrai concept, pas « *: »
        assertThat(year.get(Metric.GROSS_OPERATING_INCOME)).isPresent();
        assertThat(year.get(Metric.DIVIDENDS_PAID)).isPresent();
    }

    @Test
    void wildcardMatchesTheLocalNameOnly() {
        assertThat(MetricExtractor.matchesConcept("CASAP3_2025_FR:NetBankingIncome", "*:NetBankingIncome")).isTrue();
        assertThat(MetricExtractor.matchesConcept("SocGen:NetBankingIncomeOfFrenchRetail", "*:NetBankingIncome")).isFalse();
        assertThat(MetricExtractor.matchesConcept("ifrs-full:Revenue", "ifrs-full:Revenue")).isTrue();
    }
}
