package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.HalfYearReport;
import com.analyseparts.analysis.HalfYearReport.Item;
import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.Metric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HalfYearChecksTest {

    static final LocalDate FY = LocalDate.of(2025, 12, 31);
    static final LocalDate H1 = LocalDate.of(2026, 6, 30);

    private static FinancialHistory.Value v(String amount, Metric metric) {
        return new FinancialHistory.Value(new BigDecimal(amount), "EUR", metric.getConcepts().get(0), "rapport");
    }

    /** L'Oréal, exercice 2025 (comptes annuels ESEF). */
    static final FinancialHistory.Year OREAL_2025 = new FinancialHistory.Year(FY, Map.of(
            Metric.REVENUE, v("44052000000", Metric.REVENUE),
            Metric.OPERATING_INCOME, v("8386500000", Metric.OPERATING_INCOME),
            Metric.NET_INCOME_GROUP, v("6127200000", Metric.NET_INCOME_GROUP),
            Metric.EPS_BASIC, v("11.48", Metric.EPS_BASIC)));

    private static HalfYearReader.Line line(Item item, String cur, String prev, String year) {
        return new HalfYearReader.Line(item, item.name(), new BigDecimal(cur), new BigDecimal(prev),
                year == null ? null : new BigDecimal(year));
    }

    @Test
    void theHalfYearFollowsTheFiscalYear() {
        assertThat(HalfYearChecks.computeHalfYearEnd(FY)).isEqualTo(H1);
        assertThat(HalfYearChecks.computeHalfYearEnd(LocalDate.of(2025, 6, 30))).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(HalfYearChecks.computeHalfYearEnd(LocalDate.of(2025, 8, 31))).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void ttmIsLastYearPlusThisHalfMinusLastYearsHalf() {
        var reading = new HalfYearReader.Reading(H1, List.of(
                line(Item.REVENUE, "23776100000", "22473300000", "44052000000"),
                line(Item.NET_INCOME_GROUP, "3547100000", "3368000000", "6127200000"),
                line(Item.EPS_BASIC, "6.65", "6.31", "11.48")), List.of());

        var checked = HalfYearChecks.check(reading, OREAL_2025);
        assertThat(checked.rejected()).isEmpty();
        var report = new HalfYearReport(H1, LocalDate.of(2026, 7, 31), "RFS 2026", "url", "m", Instant.EPOCH, checked.lines());
        var ttm = HalfYearChecks.computeTtm(report, OREAL_2025);

        assertThat(ttm).extracting(t -> t.item()).containsExactly(Item.REVENUE, Item.NET_INCOME_GROUP, Item.EPS_BASIC);
        assertThat(ttm.get(0).value()).isEqualByComparingTo("45354800000"); // 44 052,0 + 23 776,1 − 22 473,3
        assertThat(ttm.get(2).value()).isEqualByComparingTo("11.82");       // 11,48 + 6,65 − 6,31
    }

    /** Vu chez LVMH : « 24 38644 », le numéro de note collé aux ventes, écrit tel quel sur la page. */
    @Test
    void anImplausibleRevenueRejectsTheWholeReport() {
        var reading = new HalfYearReader.Reading(H1, List.of(
                line(Item.REVENUE, "2438644000000", "80807000000", null),
                line(Item.NET_INCOME_GROUP, "3547100000", "3368000000", null)), List.of());

        var checked = HalfYearChecks.check(reading, OREAL_2025);

        assertThat(checked.lines()).isEmpty();
        assertThat(checked.rejected()).anyMatch(r -> r.startsWith("REVENUE"))
                .anyMatch(r -> r.contains("unité du tableau incertaine"));
    }

    @Test
    void aFullYearColumnThatDiffersFromTheAnnualAccountsRejectsTheLine() {
        var reading = new HalfYearReader.Reading(H1, List.of(
                line(Item.REVENUE, "23776100000", "22473300000", "44052000000"),
                // « Résultat d'exploitation » pris pour le résultat opérationnel : 8 891,9 au lieu de 8 386,5
                line(Item.OPERATING_INCOME, "5063000000", "4740100000", "8891900000")), List.of());

        var checked = HalfYearChecks.check(reading, OREAL_2025);

        assertThat(checked.lines()).extracting(HalfYearReport.Line::item).containsExactly(Item.REVENUE);
        assertThat(checked.rejected()).singleElement().asString().startsWith("OPERATING_INCOME");
    }

    @Test
    void aReportForAnotherHalfYearIsRejected() {
        var reading = new HalfYearReader.Reading(LocalDate.of(2025, 6, 30), List.of(
                line(Item.REVENUE, "22473300000", "21000000000", null)), List.of());
        assertThat(HalfYearChecks.check(reading, OREAL_2025).lines()).isEmpty();
    }

    @Test
    void aBankIsComparedWithItsNetBankingIncome() {
        var bank = new FinancialHistory.Year(FY, Map.of(Metric.NET_BANKING_INCOME, v("48831000000", Metric.NET_BANKING_INCOME)));
        var reading = new HalfYearReader.Reading(H1, List.of(line(Item.REVENUE, "25000000000", "24500000000", null)), List.of());
        assertThat(HalfYearChecks.check(reading, bank).lines()).hasSize(1);
    }

    /** Valeurs réellement lues dans les rapports semestriels 2026 (note collée devant le montant). */
    @Test
    void aNoteNumberGluedBeforeTheAmountIsSpotted() {
        assertThat(HalfYearChecks.hasGluedNote(Item.REVENUE, new BigDecimal("512687000000"), new BigDecimal("13028000000"))).isTrue();  // Michelin, note 5
        assertThat(HalfYearChecks.hasGluedNote(Item.REVENUE, new BigDecimal("430252000000"), new BigDecimal("27640000000"))).isTrue();  // Renault, note 4
        assertThat(HalfYearChecks.hasGluedNote(Item.REVENUE, new BigDecimal("12754007000"), new BigDecimal("759964000"))).isTrue();     // Coface, note 12
        assertThat(HalfYearChecks.hasGluedNote(Item.OPERATING_INCOME, new BigDecimal("5495100000"), new BigDecimal("371600000"))).isTrue(); // Wendel
        // une vraie hausse, une perte qui se résorbe, un BPA : rien à signaler
        assertThat(HalfYearChecks.hasGluedNote(Item.REVENUE, new BigDecimal("23776100000"), new BigDecimal("22473300000"))).isFalse();
        assertThat(HalfYearChecks.hasGluedNote(Item.OPERATING_INCOME, new BigDecimal("1126000000"), new BigDecimal("-8404000000"))).isFalse();
        assertThat(HalfYearChecks.hasGluedNote(Item.EPS_BASIC, new BigDecimal("52.10"), new BigDecimal("2.05"))).isFalse();
        // un résultat qui sextuple (Samse) n'est écarté que si le chiffre d'affaires du tableau a sa note collée
        var samse = new BigDecimal[] {new BigDecimal("8600000"), new BigDecimal("1200000")};
        assertThat(HalfYearChecks.isGlued(Item.NET_INCOME_GROUP, samse[0], samse[1], false)).isFalse();
        assertThat(HalfYearChecks.isGlued(Item.OPERATING_INCOME, new BigDecimal("5495100000"), new BigDecimal("371600000"), true)).isTrue();
    }

    @Test
    void theGluedLineIsRejectedAndTheOthersKept() {
        var reading = new HalfYearReader.Reading(H1, List.of(
                line(Item.REVENUE, "524776100000", "22473300000", null),
                line(Item.NET_INCOME_GROUP, "3547100000", "3368000000", "6127200000")), List.of());

        var checked = HalfYearChecks.check(reading, OREAL_2025);
        assertThat(checked.lines()).extracting(HalfYearReport.Line::item).containsExactly(Item.NET_INCOME_GROUP);
        assertThat(checked.rejected()).anyMatch(r -> r.contains("numéro de note"));
    }

    @Test
    void aStoredReportLeftWithoutAnchorIsEmptied() {
        var report = new HalfYearReport(H1, H1, "t", "u", "m", Instant.EPOCH, List.of(
                new HalfYearReport.Line(Item.REVENUE, "Ventes", new BigDecimal("512687000000"), new BigDecimal("13028000000"), null),
                new HalfYearReport.Line(Item.NET_INCOME_GROUP, "RN", new BigDecimal("766000000"), new BigDecimal("840000000"), null)));

        assertThat(HalfYearChecks.recheck(report, java.util.Optional.of(OREAL_2025))).isEmpty();
        assertThat(HalfYearChecks.recheck(report, java.util.Optional.empty()))
                .extracting(HalfYearReport.Line::item).containsExactly(Item.NET_INCOME_GROUP);
    }
}
