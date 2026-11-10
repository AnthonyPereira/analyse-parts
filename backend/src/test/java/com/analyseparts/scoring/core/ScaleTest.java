package com.analyseparts.scoring.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ScaleTest {

    private final Scale netMargin = Scale.of(0, 0, 0.05, 40, 0.10, 70, 0.20, 100);

    @Test
    void interpolatesBetweenAnchors() {
        assertThat(netMargin.computePoints(0.075)).isCloseTo(55, within(1e-9));
        assertThat(netMargin.computePoints(0.15)).isCloseTo(85, within(1e-9));
    }

    @Test
    void clampsOutsideTheAnchors() {
        assertThat(netMargin.computePoints(-0.30)).isZero();
        assertThat(netMargin.computePoints(0.45)).isEqualTo(100);
    }

    @Test
    void supportsDecreasingAndBellShapedScales() {
        Scale gearing = Ratio.NET_DEBT_TO_EQUITY.getScale();
        assertThat(gearing.computePoints(-0.4)).isEqualTo(100); // plus de trésorerie que de dettes
        assertThat(gearing.computePoints(1.5)).isCloseTo(20, within(1e-9));

        Scale payout = Ratio.PAYOUT_RATIO.getScale();
        assertThat(payout.computePoints(0.5)).isEqualTo(100);
        assertThat(payout.computePoints(1.2)).isCloseTo(12, within(1e-9));
    }

    @Test
    void rejectsUnsortedAnchors() {
        assertThatThrownBy(() -> Scale.of(0.1, 0, 0.05, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
