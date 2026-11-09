package com.analyseparts.scoring;

import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialHistory.Value;
import com.analyseparts.financials.Metric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsuranceRevenueTest {

    private static Value value(long amount, String concept) {
        return new Value(BigDecimal.valueOf(amount), "EUR", concept, "rapport");
    }

    private static FinancialHistory.Year year(Map<Metric, Value> metrics) {
        return new FinancialHistory.Year(LocalDate.of(2025, 12, 31), metrics);
    }

    @Test
    void insuranceRevenueStandsForMissingRevenue() {
        // SCOR : pas de chiffre d'affaires total, seulement le revenu des contrats (IFRS 17)
        FinancialHistory scor = new FinancialHistory(List.of(year(Map.of(
                Metric.INSURANCE_REVENUE, value(15_378, "ifrs-full:InsuranceRevenue")))));

        FinancialHistory adjusted = ScoringService.withInsuranceRevenue(scor);

        assertThat(adjusted.years().getFirst().get(Metric.REVENUE))
                .hasValueSatisfying(v -> assertThat(v.concept()).isEqualTo("ifrs-full:InsuranceRevenue"));
    }

    @Test
    void publishedRevenueIsKept() {
        // AXA publie un total (91,1 Md) plus large que le seul revenu d'assurance (90,0 Md)
        FinancialHistory axa = new FinancialHistory(List.of(year(Map.of(
                Metric.REVENUE, value(91_139, "ifrs-full:Revenue"),
                Metric.INSURANCE_REVENUE, value(89_953, "ifrs-full:InsuranceRevenue")))));

        FinancialHistory adjusted = ScoringService.withInsuranceRevenue(axa);

        assertThat(adjusted.years().getFirst().get(Metric.REVENUE))
                .hasValueSatisfying(v -> assertThat(v.value()).isEqualByComparingTo("91139"));
    }
}
