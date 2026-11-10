package com.analyseparts.market;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"analyse-parts.ingestion.auto-lei=false", "analyse-parts.amf.enabled=false"})
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class MarketDataIntegrationTest {

    @Autowired
    CompanyRepository companies;
    @Autowired
    MarketDataService market;
    @Autowired
    ApplicationEvents events;

    @Test
    void onlyChangedQuotesArePushedLive() {
        long id = companies.save(new Company("FR0000000002", "COURS SA", "CRS", "FR", "Euronext Paris")).getId();
        Instant t = Instant.parse("2026-11-06T14:00:00Z");

        assertThat(market.recordQuotes(List.of(new Quote(id, new BigDecimal("10.00"), "EUR", t, "YAHOO",
                new BigDecimal("9.50"))))).isEqualTo(1);
        // même cours, même heure : rien à diffuser
        assertThat(market.recordQuotes(List.of(new Quote(id, new BigDecimal("10.00"), "EUR", t, "YAHOO",
                new BigDecimal("9.50"))))).isZero();
        // cours plus ancien : ignoré
        assertThat(market.recordQuotes(List.of(new Quote(id, new BigDecimal("11"), "EUR", t.minusSeconds(60), "YAHOO"))))
                .isZero();
        // nouveau cours : diffusé, et la clôture de la veille est conservée
        assertThat(market.recordQuotes(List.of(new Quote(id, new BigDecimal("10.20"), "EUR", t.plusSeconds(60), "YAHOO"))))
                .isEqualTo(1);

        assertThat(events.stream(MarketDataService.QuotesUpdated.class)).hasSize(2);
        assertThat(market.findLatestQuote(id).orElseThrow().previousClose()).isEqualByComparingTo("9.50");
    }

    @Test
    void closeOnAFiscalYearEndFallsBackToTheLastTradingDay() {
        long id = companies.save(new Company("FR0000000003", "HISTO SA", "HST", "FR", "Euronext Paris")).getId();
        market.recordCloses(id, List.of(
                new PricePoint.Close(LocalDate.of(2025, 12, 30), new BigDecimal("50"), 1000L),
                new PricePoint.Close(LocalDate.of(2025, 12, 31), new BigDecimal("51"), 900L),
                new PricePoint.Close(LocalDate.of(2026, 1, 2), new BigDecimal("52"), null)));

        // le 31/12/2026 n'existe pas encore, le 03/01/2026 est un samedi
        assertThat(market.findCloseOn(id, LocalDate.of(2026, 1, 3)).orElseThrow().close()).isEqualByComparingTo("52");
        assertThat(market.findCloseOn(id, LocalDate.of(2025, 12, 31)).orElseThrow().close()).isEqualByComparingTo("51");
        assertThat(market.findCloseOn(id, LocalDate.of(2024, 12, 31))).isEmpty();
        assertThat(market.findCloses(id, LocalDate.of(2025, 12, 31))).hasSize(2);
    }

    /**
     * Au-delà d'un an, la dernière clôture de chaque semaine et de chaque mois (ADR 0020) ; la dernière
     * année reste quotidienne.
     */
    @Test
    void oldClosesAreThinnedToTheLastOfEachWeekAndMonth() {
        long id = companies.save(new Company("FR0000000004", "SEMAINE SA", "SEM", "FR", "Euronext Paris")).getId();
        LocalDate oldMonday = LocalDate.of(2024, 7, 29); // semaine du mercredi 31 juillet, fin de mois
        LocalDate recentMonday = LocalDate.now().minusWeeks(2).with(java.time.DayOfWeek.MONDAY);
        List<PricePoint.Close> closes = new java.util.ArrayList<>();
        for (int d = 0; d < 5; d++) {
            closes.add(new PricePoint.Close(oldMonday.plusDays(d), new BigDecimal(10 + d), null));
            closes.add(new PricePoint.Close(recentMonday.plusDays(d), new BigDecimal(20 + d), null));
        }
        market.recordCloses(id, closes);

        assertThat(market.thinOldCloses()).isGreaterThanOrEqualTo(3);
        List<PricePoint.Close> kept = market.findCloses(id, oldMonday);
        assertThat(kept).hasSize(7);
        assertThat(kept.get(0).day()).isEqualTo(LocalDate.of(2024, 7, 31)); // fin de mois : clôture d'exercice possible
        assertThat(kept.get(1).day()).isEqualTo(LocalDate.of(2024, 8, 2)); // fin de semaine
    }
}
