package com.analyseparts.ingestion;

import com.analyseparts.ingestion.SourceHealth.Source;
import com.analyseparts.ingestion.SourceHealth.State;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SourceHealthTest {

    /** Une horloge qu'on avance à la main. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final TestClock clock = new TestClock();
    final SourceHealth health = new SourceHealth(clock);

    @Test
    void unknownUntilTheFirstCollection() {
        assertThat(health.of(Source.YAHOO_QUOTES).state()).isEqualTo(State.UNKNOWN);
        health.report(Source.YAHOO_QUOTES, 0, 0, null); // rien à faire : pas un verdict
        assertThat(health.of(Source.YAHOO_QUOTES).state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void stateFollowsTheSuccessRate() {
        health.report(Source.YAHOO_QUOTES, 330, 321, null); // 97 % : normal
        assertThat(health.of(Source.YAHOO_QUOTES).state()).isEqualTo(State.UP);
        assertThat(health.of(Source.YAHOO_QUOTES).problem()).isNull();

        health.report(Source.YAHOO_QUOTES, 330, 150, "trop peu de cours");
        assertThat(health.of(Source.YAHOO_QUOTES).state()).isEqualTo(State.DEGRADED);

        health.report(Source.YAHOO_QUOTES, 330, 0, "format changé");
        assertThat(health.of(Source.YAHOO_QUOTES).state()).isEqualTo(State.DOWN);
        assertThat(health.of(Source.YAHOO_QUOTES).problem()).isEqualTo("format changé");
    }

    @Test
    void theProblemKeepsItsStartDateAndTheLastSuccessIsRemembered() {
        health.report(Source.YAHOO_QUOTES, 330, 321, null);
        Instant lastGood = clock.now;

        clock.now = clock.now.plus(Duration.ofMinutes(1));
        health.report(Source.YAHOO_QUOTES, 330, 0, "format changé");
        Instant start = clock.now;
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        health.report(Source.YAHOO_QUOTES, 330, 0, "format changé");

        var status = health.of(Source.YAHOO_QUOTES);
        assertThat(status.problemSince()).isEqualTo(start);
        assertThat(status.lastSuccess()).isEqualTo(lastGood);
        assertThat(status.lastCheck()).isEqualTo(clock.now);

        health.report(Source.YAHOO_QUOTES, 330, 320, null);
        assertThat(health.of(Source.YAHOO_QUOTES).problemSince()).isNull();
    }

    @Test
    void aTallyCountsAttemptsAndKeepsTheFirstProblem() {
        SourceHealth.Tally tally = new SourceHealth.Tally();
        tally.ok();
        tally.fail("premier problème");
        tally.fail("second problème");
        health.report(Source.AMF, tally);

        var status = health.of(Source.AMF);
        assertThat(status.detail()).isEqualTo("1 sur 3");
        assertThat(status.state()).isEqualTo(State.DEGRADED);
        assertThat(status.problem()).isEqualTo("premier problème");
    }
}
