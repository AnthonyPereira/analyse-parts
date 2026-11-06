package com.analyseparts.ingestion.esef;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** L'AMF d'abord ; filings.xbrl.org seulement pour ce qui manque. */
class EsefSourcePriorityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void noComplementWhenTheAmfGaveTheLastThreeYears() {
        Set<LocalDate> known = Set.of(LocalDate.of(2025, 12, 31), LocalDate.of(2024, 12, 31), LocalDate.of(2023, 12, 31));
        assertThat(EsefImportService.needsComplement(known, TODAY, 3)).isFalse();
    }

    @Test
    void complementWhenYearsAreMissing() {
        assertThat(EsefImportService.needsComplement(Set.of(LocalDate.of(2025, 12, 31)), TODAY, 3)).isTrue();
        // trou en 2024
        Set<LocalDate> gap = Set.of(LocalDate.of(2025, 12, 31), LocalDate.of(2023, 12, 31), LocalDate.of(2022, 12, 31));
        assertThat(EsefImportService.needsComplement(gap, TODAY, 3)).isTrue();
    }

    @Test
    void complementWhenNothingOrOnlyOldData() {
        assertThat(EsefImportService.needsComplement(Set.of(), TODAY, 3)).isTrue(); // ex. société étrangère
        Set<LocalDate> old = Set.of(LocalDate.of(2024, 12, 31), LocalDate.of(2023, 12, 31), LocalDate.of(2022, 12, 31));
        assertThat(EsefImportService.needsComplement(old, TODAY, 3)).isTrue(); // le rapport 2025 manque
    }

    @Test
    void sameYearWrittenDifferentlyIsKnown() {
        Set<LocalDate> known = Set.of(LocalDate.of(2025, 12, 31));
        assertThat(EsefImportService.isKnown(known, LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(EsefImportService.isKnown(known, LocalDate.of(2025, 6, 30))).isFalse();
    }
}
