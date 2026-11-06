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
        assertThat(market.latestQuote(id).orElseThrow().previousClose()).isEqualByComparingTo("9.50");
    }

    @Test
    void closeOnAFiscalYearEndFallsBackToTheLastTradingDay() {
        long id = companies.save(new Company("FR0000000003", "HISTO SA", "HST", "FR", "Euronext Paris")).getId();
        market.recordCloses(id, List.of(
                new PricePoint.Close(LocalDate.of(2025, 12, 30), new BigDecimal("50"), 1000L),
                new PricePoint.Close(LocalDate.of(2025, 12, 31), new BigDecimal("51"), 900L),
                new PricePoint.Close(LocalDate.of(2026, 1, 2), new BigDecimal("52"), null)));

        // le 31/12/2026 n'existe pas encore, le 03/01/2026 est un samedi
        assertThat(market.closeOn(id, LocalDate.of(2026, 1, 3)).orElseThrow().close()).isEqualByComparingTo("52");
        assertThat(market.closeOn(id, LocalDate.of(2025, 12, 31)).orElseThrow().close()).isEqualByComparingTo("51");
        assertThat(market.closeOn(id, LocalDate.of(2024, 12, 31))).isEmpty();
        assertThat(market.closes(id, LocalDate.of(2025, 12, 31))).hasSize(2);
    }
}
