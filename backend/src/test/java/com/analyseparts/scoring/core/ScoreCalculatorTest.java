package com.analyseparts.scoring.core;

import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioCalculator.RatioValue;
import com.analyseparts.scoring.core.ScoreCalculator.Score;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoreCalculatorTest {

    private static final LocalDate ASOF = LocalDate.of(2026, 11, 6);

    private static RatioSet ratios(Map<Ratio, Double> values, Map<Ratio, String> unavailable) {
        Map<Ratio, RatioValue> map = new EnumMap<>(Ratio.class);
        values.forEach((r, v) -> map.put(r, new RatioValue(r, v, List.of())));
        return new RatioSet(LocalDate.of(2025, 12, 31), map, unavailable);
    }

    @Test
    void weightedAverageOfPoints() {
        // Marge nette 10 % → 70 points ; ROE 25 % → 100 points ; poids 1 pour 3
        Score s = ScoreCalculator.score(
                ratios(Map.of(Ratio.NET_MARGIN, 0.10, Ratio.ROE, 0.25), Map.of()),
                Map.of(Ratio.NET_MARGIN, 25, Ratio.ROE, 75), ASOF);

        assertThat(s.score()).isEqualTo(93); // 70 × 0,25 + 100 × 0,75 = 92,5
        assertThat(s.coverage()).isEqualTo(1.0);
        assertThat(s.contributions()).extracting(ScoreCalculator.Contribution::ratio)
                .containsExactly(Ratio.ROE, Ratio.NET_MARGIN); // la plus forte contribution d'abord
        assertThat(s.familyScore()).containsEntry(RatioFamily.PROFITABILITY, 93);
    }

    @Test
    void missingRatioWeightIsSpreadOverTheOthers() {
        Score s = ScoreCalculator.score(
                ratios(Map.of(Ratio.NET_MARGIN, 0.10, Ratio.ROE, 0.25), Map.of(Ratio.FCF_MARGIN, "Donnée manquante")),
                Map.of(Ratio.NET_MARGIN, 25, Ratio.ROE, 45, Ratio.FCF_MARGIN, 30), ASOF);

        // 70 × 25/70 + 100 × 45/70 = 89,3
        assertThat(s.score()).isEqualTo(89);
        assertThat(s.coverage()).isCloseTo(0.70, within(1e-9));
        assertThat(s.missing()).singleElement()
                .satisfies(m -> assertThat(m.reason()).isEqualTo("Donnée manquante"));
        double sum = s.contributions().stream().mapToDouble(ScoreCalculator.Contribution::contribution).sum();
        assertThat(sum).isCloseTo(89.3, within(0.1)); // les contributions expliquent le score
    }

    @Test
    void noScoreBelowMinimumCoverage() {
        Score s = ScoreCalculator.score(
                ratios(Map.of(Ratio.NET_MARGIN, 0.10), Map.of()),
                Map.of(Ratio.NET_MARGIN, 50, Ratio.ROE, 50), ASOF);

        assertThat(s.score()).isNull();
        assertThat(s.coverage()).isEqualTo(0.5);
    }

    @Test
    void lateFiguresAreFlaggedAndObsoleteOnesGetNoScore() {
        RatioSet r = ratios(Map.of(Ratio.NET_MARGIN, 0.10), Map.of()); // exercice clos le 31/12/2025
        Map<Ratio, Integer> w = Map.of(Ratio.NET_MARGIN, 100);

        assertThat(ScoreCalculator.score(r, w, LocalDate.of(2026, 11, 6)).freshness()).isEqualTo(Freshness.UP_TO_DATE);
        Score late = ScoreCalculator.score(r, w, LocalDate.of(2027, 6, 1));
        assertThat(late.freshness()).isEqualTo(Freshness.LATE);
        assertThat(late.score()).isEqualTo(70);
        Score obsolete = ScoreCalculator.score(r, w, LocalDate.of(2028, 6, 1));
        assertThat(obsolete.freshness()).isEqualTo(Freshness.OBSOLETE);
        assertThat(obsolete.score()).isNull();
    }

    @Test
    void everyProfileHasWeightsSummingTo100() {
        for (Profile p : Profile.values()) {
            assertThat(p.weights().values().stream().mapToInt(Integer::intValue).sum()).as(p.name()).isEqualTo(100);
        }
    }
}
