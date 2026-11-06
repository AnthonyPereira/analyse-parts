package com.analyseparts.ingestion.esef;

import com.analyseparts.ingestion.esef.FilingsXbrlClient.FilingInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilingSelectorTest {

    private static FilingInfo filing(String id, String periodEnd, String country, int errors) {
        return new FilingInfo(id, periodEnd, country, "/" + id + ".json", errors, "2026-03-01");
    }

    @Test
    void oneFilingPerPeriodPreferringFewerErrorsThenCompanyCountry() {
        var selected = FilingSelector.select(List.of(
                filing("2025-GB", "2025-12-31", "GB", 0),
                filing("2025-FR", "2025-12-31", "FR", 0),
                filing("2024-FR", "2024-12-31", "FR", 3),
                filing("2024-GB", "2024-12-31", "GB", 0)), "FR", 5);

        assertThat(selected).extracting(FilingInfo::fxoId).containsExactly("2025-FR", "2024-GB");
    }

    @Test
    void keepsTheMostRecentPeriods() {
        var selected = FilingSelector.select(List.of(
                filing("a", "2022-12-31", "FR", 0),
                filing("b", "2025-12-31", "FR", 0),
                filing("c", "2023-12-31", "FR", 0),
                filing("d", "2024-12-31", "FR", 0)), "FR", 2);

        assertThat(selected).extracting(FilingInfo::fxoId).containsExactly("b", "d");
    }
}
