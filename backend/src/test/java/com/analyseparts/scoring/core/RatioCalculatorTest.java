package com.analyseparts.scoring.core;

import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialHistory.Year;
import com.analyseparts.financials.Metric;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.analyseparts.financials.Metric.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RatioCalculatorTest {

    /** Un exercice clos le 31/12 de l'année donnée, en millions d'euros. */
    private static Year year(int year, Object... metricValuePairs) {
        Map<Metric, FinancialHistory.Value> metrics = new EnumMap<>(Metric.class);
        for (int i = 0; i < metricValuePairs.length; i += 2) {
            metrics.put((Metric) metricValuePairs[i], new FinancialHistory.Value(
                    new BigDecimal(metricValuePairs[i + 1].toString()), "EUR", "ifrs-full:x", "f" + year));
        }
        return new Year(LocalDate.of(year, 12, 31), metrics);
    }

    /** Une société industrielle : 3 exercices, ventes de 1 000 → 1 210. */
    private static final FinancialHistory INDUSTRIAL = new FinancialHistory(List.of(
            year(2025, REVENUE, 1210, OPERATING_INCOME, 181.5, NET_INCOME, 125, NET_INCOME_GROUP, 121,
                    EQUITY, 1050, EQUITY_GROUP, 1000, TOTAL_ASSETS, 2500, CURRENT_ASSETS, 900, CURRENT_LIABILITIES, 600,
                    LONG_TERM_DEBT, 400, SHORT_TERM_DEBT, 100, CASH, 200,
                    OPERATING_CASH_FLOW, 150, CAPEX, 60, DIVIDENDS_PAID, 60, EPS_BASIC, 2.42),
            year(2024, REVENUE, 1100, NET_INCOME, 110, NET_INCOME_GROUP, 100, EQUITY_GROUP, 900,
                    TOTAL_ASSETS, 2300, DIVIDENDS_PAID, 55, EPS_BASIC, 2.0),
            year(2023, REVENUE, 1000, NET_INCOME, -20, NET_INCOME_GROUP, -20, DIVIDENDS_PAID, 50, EPS_BASIC, -0.4)));

    @Test
    void profitabilityRatios() {
        RatioSet r = RatioCalculator.compute(INDUSTRIAL);

        assertThat(r.fiscalYearEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(r.get(Ratio.NET_MARGIN).orElseThrow().value()).isCloseTo(0.10, within(1e-9));
        assertThat(r.get(Ratio.OPERATING_MARGIN).orElseThrow().value()).isCloseTo(0.15, within(1e-9));
        // ROE : 121 ÷ moyenne(900, 1 000) = 121 ÷ 950
        assertThat(r.get(Ratio.ROE).orElseThrow().value()).isCloseTo(121.0 / 950, within(1e-9));
        // ROA : 125 ÷ moyenne(2 300, 2 500)
        assertThat(r.get(Ratio.ROA).orElseThrow().value()).isCloseTo(125.0 / 2400, within(1e-9));
    }

    @Test
    void solidityAndCashRatios() {
        RatioSet r = RatioCalculator.compute(INDUSTRIAL);

        // (400 + 100 − 200) ÷ 1 050
        assertThat(r.get(Ratio.NET_DEBT_TO_EQUITY).orElseThrow().value()).isCloseTo(300.0 / 1050, within(1e-9));
        assertThat(r.get(Ratio.EQUITY_RATIO).orElseThrow().value()).isCloseTo(0.42, within(1e-9));
        assertThat(r.get(Ratio.CURRENT_RATIO).orElseThrow().value()).isCloseTo(1.5, within(1e-9));
        assertThat(r.get(Ratio.CASH_CONVERSION).orElseThrow().value()).isCloseTo(1.2, within(1e-9));
        // (150 − 60) ÷ 1 210
        assertThat(r.get(Ratio.FCF_MARGIN).orElseThrow().value()).isCloseTo(90.0 / 1210, within(1e-9));
    }

    @Test
    void trendRatiosUseTheWholeHistory() {
        RatioSet r = RatioCalculator.compute(INDUSTRIAL);

        // 1 000 → 1 210 en 2 ans : +10 % par an
        assertThat(r.get(Ratio.REVENUE_GROWTH).orElseThrow().value()).isCloseTo(0.10, within(1e-3));
        // 2 exercices bénéficiaires sur 3
        assertThat(r.get(Ratio.PROFITABLE_YEARS).orElseThrow().value()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(r.get(Ratio.PAYOUT_RATIO).orElseThrow().value()).isCloseTo(60.0 / 121, within(1e-9));
        // BPA négatif au départ : pas de croissance calculable
        assertThat(r.unavailable().get(Ratio.EPS_GROWTH)).contains("négative");
    }

    @Test
    void bankBalanceSheetHasNoIndustrialDebtRatios() {
        // Une banque : pas de chiffre d'affaires ni d'actifs courants
        RatioSet r = RatioCalculator.compute(new FinancialHistory(List.of(
                year(2025, NET_INCOME, 12000, NET_INCOME_GROUP, 11000, EQUITY, 130000, EQUITY_GROUP, 125000,
                        TOTAL_ASSETS, 2800000, CASH, 180000, OPERATING_CASH_FLOW, 46000))));

        assertThat(r.unavailable().get(Ratio.EQUITY_RATIO)).contains("ordre de liquidité");
        assertThat(r.unavailable().get(Ratio.NET_DEBT_TO_EQUITY)).contains("ordre de liquidité");
        assertThat(r.unavailable().get(Ratio.NET_MARGIN)).contains("chiffre d'affaires");
        assertThat(r.get(Ratio.ROE)).isPresent();
        assertThat(r.get(Ratio.ROA)).isPresent();
    }

    @Test
    void negativeEquityMakesRoeUnavailable() {
        RatioSet r = RatioCalculator.compute(new FinancialHistory(List.of(
                year(2025, NET_INCOME, 50, NET_INCOME_GROUP, 50, EQUITY, -100, EQUITY_GROUP, -100))));

        assertThat(r.get(Ratio.ROE)).isEmpty();
        assertThat(r.unavailable().get(Ratio.ROE)).contains("négative ou nulle");
    }

    @Test
    void emptyHistory() {
        RatioSet r = RatioCalculator.compute(new FinancialHistory(List.of()));

        assertThat(r.values()).isEmpty();
        assertThat(r.unavailable()).hasSize(Ratio.values().length);
    }
}
