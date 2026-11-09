package com.analyseparts.ingestion.esef;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EsefRefreshPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant YESTERDAY = NOW.minus(1, ChronoUnit.DAYS);
    private static final Instant LAST_WEEK = NOW.minus(8, ChronoUnit.DAYS);

    @Test
    void neverCheckedIsDue() {
        assertThat(EsefRefreshPolicy.isDue(null, null, NOW, false)).isTrue();
    }

    @Test
    void upToDateReportIsNotDue() {
        // exercice 2025 : le rapport 2026 n'est attendu qu'à partir de janvier 2027
        LocalDate fy2025 = LocalDate.of(2025, 12, 31);
        assertThat(EsefRefreshPolicy.isDue(LAST_WEEK, fy2025, NOW, false)).isFalse();
        assertThat(EsefRefreshPolicy.isDue(LAST_WEEK, fy2025, NOW, true)).isFalse();
    }

    @Test
    void lateReportIsCheckedWeekly() {
        // dernier exercice connu 2024 (LVMH) : le rapport 2025 devrait exister
        LocalDate fy2024 = LocalDate.of(2024, 12, 31);
        assertThat(EsefRefreshPolicy.isDue(LAST_WEEK, fy2024, NOW, false)).isTrue();
        assertThat(EsefRefreshPolicy.isDue(YESTERDAY, fy2024, NOW, false)).isFalse();
        assertThat(EsefRefreshPolicy.isDue(YESTERDAY, fy2024, NOW, true)).isTrue(); // relance manuelle
    }

    @Test
    void dailyAmfCheckOnlyForExpectedReports() {
        assertThat(EsefRefreshPolicy.isReportExpected(LocalDate.of(2024, 12, 31), NOW)).isTrue();
        assertThat(EsefRefreshPolicy.isReportExpected(LocalDate.of(2025, 12, 31), NOW)).isFalse();
        assertThat(EsefRefreshPolicy.isReportExpected(null, NOW)).isFalse(); // sans rapport : vérification mensuelle
    }

    @Test
    void companyWithoutFilingIsCheckedMonthly() {
        assertThat(EsefRefreshPolicy.isDue(LAST_WEEK, null, NOW, false)).isFalse();
        assertThat(EsefRefreshPolicy.isDue(NOW.minus(31, ChronoUnit.DAYS), null, NOW, false)).isTrue();
    }
}
