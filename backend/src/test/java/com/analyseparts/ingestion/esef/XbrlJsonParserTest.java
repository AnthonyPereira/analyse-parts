package com.analyseparts.ingestion.esef;

import com.analyseparts.financials.ReportedFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.XbrlFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.XbrlJson;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XbrlJsonParserTest {

    private static final String YEAR_2025 = "2025-01-01T00:00:00/2026-01-01T00:00:00";

    private static XbrlFact fact(String value, String concept, String period, String unit, String... axis) {
        Map<String, String> dims = new LinkedHashMap<>();
        dims.put("concept", concept);
        dims.put("entity", "scheme:529900S21EQ1BO4ESM68");
        dims.put("period", period);
        if (unit != null) {
            dims.put("unit", unit);
        }
        for (int i = 0; i < axis.length; i += 2) {
            dims.put(axis[i], axis[i + 1]);
        }
        return new XbrlFact(value, -6, dims);
    }

    private static List<ReportedFact> parse(XbrlFact... facts) {
        Map<String, XbrlFact> map = new LinkedHashMap<>();
        for (int i = 0; i < facts.length; i++) {
            map.put("f" + i, facts[i]);
        }
        return XbrlJsonParser.totals(new XbrlJson(map));
    }

    @Test
    void keepsTotalsAndDropsAxesTextAndDuplicates() {
        var facts = parse(
                fact("13357000000.0", "ifrs-full:ProfitLoss", YEAR_2025, "iso4217:USD"),
                fact("13357000000.0", "ifrs-full:ProfitLoss", YEAR_2025, "iso4217:USD"),     // repris dans un 2e tableau
                fact("7059000000.0", "ifrs-full:Equity", "2026-01-01T00:00:00", "iso4217:USD",
                        "ifrs-full:ComponentsOfEquityAxis", "ifrs-full:SharePremiumMember"),  // ventilation
                fact("société européenne", "ifrs-full:LegalFormOfEntity", YEAR_2025, null)); // texte

        assertThat(facts).containsExactly(new ReportedFact("ifrs-full:ProfitLoss",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "iso4217:USD",
                new BigDecimal("13357000000.0"), -6));
    }

    @Test
    void exclusiveMidnightEndMeansPreviousDay() {
        var facts = parse(fact("117523000000.0", "ifrs-full:Equity", "2026-01-01T00:00:00", "iso4217:USD"));

        assertThat(facts.getFirst().periodStart()).isNull();
        assertThat(facts.getFirst().periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void plainDatesAreKeptAsIs() {
        assertThat(XbrlJsonParser.Period.parse("2024-07-01/2025-06-30"))
                .isEqualTo(new XbrlJsonParser.Period(LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30)));
    }
}
