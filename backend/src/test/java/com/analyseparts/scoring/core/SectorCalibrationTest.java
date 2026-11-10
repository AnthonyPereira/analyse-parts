package com.analyseparts.scoring.core;

import com.analyseparts.company.Icb;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioCalculator.RatioValue;
import com.analyseparts.scoring.core.SectorCalibration.Sample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SectorCalibrationTest {

    private static final Icb RETAIL = new Icb("40401010", "Consumer Discretionary", "Retail", "Retailers", "Diversified Retailers");
    private static final Icb SOFTWARE = new Icb("10101015", "Technology", "Technology", "Software and Computer Services", "Software");
    private static final Icb BANK = new Icb("30101010", "Financials", "Banks", "Banks", "Banks");
    private static final Icb INSURANCE = new Icb("30302010", "Financials", "Insurance", "Life Insurance", "Life Insurance");

    private static Sample sample(Icb icb, double netMargin) {
        RatioSet ratios = new RatioSet(LocalDate.of(2025, 12, 31),
                Map.of(Ratio.NET_MARGIN, new RatioValue(Ratio.NET_MARGIN, netMargin, List.of())), Map.of());
        return new Sample(icb, ratios);
    }

    /** 10 distributeurs à 2 % de marge, 10 éditeurs de logiciels à 20 %, 3 assureurs à 25 %, 2 banques à 30 %. */
    private static SectorCalibration calibration() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(sample(RETAIL, 0.02));
            samples.add(sample(SOFTWARE, 0.20));
        }
        for (int i = 0; i < 3; i++) {
            samples.add(sample(INSURANCE, 0.25));
        }
        samples.add(sample(BANK, 0.30));
        samples.add(sample(BANK, 0.30));
        return SectorCalibration.compute(samples, Instant.parse("2026-11-06T00:00:00Z"));
    }

    @Test
    void factorIsSectorMedianOverUniverseMedian() {
        SectorCalibration c = calibration();

        // univers : 25 valeurs, médiane = 20 %
        var retail = c.findAdjustment(Ratio.NET_MARGIN, RETAIL).orElseThrow();
        assertThat(retail.level()).isEqualTo(SectorCalibration.SUPERSECTOR);
        assertThat(retail.sectorMedian()).isCloseTo(0.02, within(1e-9));
        assertThat(retail.universeMedian()).isCloseTo(0.20, within(1e-9));
        assertThat(retail.factor()).isEqualTo(SectorCalibration.MIN_FACTOR); // 0,1 borné à 0,4

        assertThat(c.findAdjustment(Ratio.NET_MARGIN, SOFTWARE).orElseThrow().factor()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void smallSupersectorsKeepTheirOwnMedian() {
        var insurance = calibration().findAdjustment(Ratio.NET_MARGIN, INSURANCE).orElseThrow(); // 3 sociétés suffisent
        assertThat(insurance.level()).isEqualTo(SectorCalibration.SUPERSECTOR);
        assertThat(insurance.sectorMedian()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void belowThreeCompaniesTheIndustryOrGeneralScaleApplies() {
        // 2 banques : trop peu ; l'industrie Financials (2 + 3 = 5) n'atteint pas 8 non plus
        assertThat(calibration().findAdjustment(Ratio.NET_MARGIN, BANK)).isEmpty();
    }

    @Test
    void absoluteRatiosAreNeverAdjusted() {
        assertThat(calibration().findAdjustment(Ratio.ROE, RETAIL)).isEmpty();
        assertThat(calibration().findAdjustment(Ratio.NET_MARGIN, null)).isEmpty();
    }

    @Test
    void adjustedScaleChangesThePoints() {
        // Avec un facteur 0,4, une marge de 4 % vaut autant qu'une marge de 10 % sur le barème général
        Scale adjusted = Ratio.NET_MARGIN.getScale().scaleBy(0.4);
        assertThat(adjusted.computePoints(0.04)).isCloseTo(Ratio.NET_MARGIN.getScale().computePoints(0.10), within(1e-9));
    }

    @Test
    void median() {
        assertThat(SectorCalibration.computeMedian(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(SectorCalibration.computeMedian(List.of(4.0, 1.0, 2.0, 3.0))).isEqualTo(2.5);
    }
}
