package com.analyseparts.ingestion.prices;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketClientsTest {

    @Test
    void yahooSparkReturnsOneQuotePerSymbol() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://yahoo.test/v7/finance/spark?symbols=MC.PA%2CAIR.PA&range=1d&interval=1d"))
                .andRespond(withSuccess("""
                        {"spark":{"result":[
                          {"symbol":"MC.PA","response":[{"meta":{"currency":"EUR","symbol":"MC.PA","exchangeName":"PAR",
                            "regularMarketPrice":403.75,"regularMarketTime":1793973274,"chartPreviousClose":418.65}}]},
                          {"symbol":"AIR.PA","response":[{"meta":{"currency":"EUR","symbol":"AIR.PA","exchangeName":"PAR",
                            "regularMarketPrice":196.54,"regularMarketTime":1793973278}}]}]}}
                        """, MediaType.APPLICATION_JSON));

        var quotes = new YahooQuoteClient(builder, "https://yahoo.test").fetchQuotes(List.of("MC.PA", "AIR.PA"));

        assertThat(quotes).hasSize(2);
        assertThat(quotes.getFirst()).isEqualTo(new YahooQuoteClient.YahooQuote("MC.PA", new BigDecimal("403.75"),
                "EUR", "PAR", Instant.ofEpochSecond(1793973274), new BigDecimal("418.65")));
        server.verify();
    }

    /** Un champ renommé chez Yahoo : des résultats, mais plus aucun cours exploitable, et on le sait. */
    @Test
    void yahooSparkWithARenamedFieldIsReportedAsAFormatChange() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://yahoo.test/v7/finance/spark?symbols=MC.PA%2CAIR.PA&range=1d&interval=1d"))
                .andRespond(withSuccess("""
                        {"spark":{"result":[
                          {"symbol":"MC.PA","response":[{"meta":{"currency":"EUR","symbol":"MC.PA","exchangeName":"PAR",
                            "lastPrice":403.75,"regularMarketTime":1793973274}}]},
                          {"symbol":"AIR.PA","response":[{"meta":{"currency":"EUR","symbol":"AIR.PA","exchangeName":"PAR",
                            "lastPrice":196.54,"regularMarketTime":1793973278}}]}]}}
                        """, MediaType.APPLICATION_JSON));

        var batch = new YahooQuoteClient(builder, "https://yahoo.test").fetchSpark(List.of("MC.PA", "AIR.PA"));

        assertThat(batch.results()).isEqualTo(2);
        assertThat(batch.usable()).isEmpty();
        assertThat(batch.looksLikeFormatChange()).isTrue();
    }

    @Test
    void yahooChartWithoutMetaFailsClearly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://yahoo.test/v8/finance/chart/MC.PA?range=5d&interval=1d"))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"timestamp":[1793952000],"indicators":{"quote":[{"close":[417.1]}]}}]}}
                        """, MediaType.APPLICATION_JSON));

        var client = new YahooQuoteClient(builder, "https://yahoo.test");

        assertThatThrownBy(() -> client.fetchChart("MC.PA", "5d", "1d"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("meta absent");
    }

    @Test
    void yahooChartSkipsSlotsWithoutTrades() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://yahoo.test/v8/finance/chart/MC.PA?range=1d&interval=5m"))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"meta":{"currency":"EUR","symbol":"MC.PA","exchangeName":"PAR",
                          "previousClose":418.65,"chartPreviousClose":418.65},
                          "timestamp":[1793952000,1793952300,1793952600],
                          "indicators":{"quote":[{"close":[417.1,null,416.4],"volume":[1200,null,800]}]}}]}}
                        """, MediaType.APPLICATION_JSON));

        var chart = new YahooQuoteClient(builder, "https://yahoo.test").fetchChart("MC.PA", "1d", "5m").orElseThrow();

        assertThat(chart.previousClose()).isEqualByComparingTo("418.65");
        assertThat(chart.points()).extracting(YahooQuoteClient.YahooChart.Point::close)
                .containsExactly(new BigDecimal("417.1"), new BigDecimal("416.4"));
        assertThat(chart.points().get(1).volume()).isEqualTo(800L);
        server.verify();
    }

    @Test
    void liveQuotesOnlyDuringTheSession() {
        ZonedDateTime friday = ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, QuoteRefreshService.PARIS);
        assertThat(QuoteRefreshService.isMarketHours(friday.withHour(8).withMinute(59))).isFalse();
        assertThat(QuoteRefreshService.isMarketHours(friday.withHour(9))).isTrue();
        // ~15 min de différé chez Yahoo : la clôture de 17 h 35 arrive vers 17 h 50
        assertThat(QuoteRefreshService.isMarketHours(friday.withHour(17).withMinute(50))).isTrue();
        assertThat(QuoteRefreshService.isMarketHours(friday.withHour(18))).isFalse();
        assertThat(QuoteRefreshService.isMarketHours(friday.plusDays(1).withHour(11))).isFalse(); // samedi
    }

    @Test
    void ecbDailyRates() {
        var rates = EcbFxClient.parse("""
                <gesmes:Envelope><Cube><Cube time='2026-11-05'>
                  <Cube currency='USD' rate='1.0812'/><Cube currency='GBP' rate='0.8412'/>
                </Cube></Cube></gesmes:Envelope>
                """);

        assertThat(rates.date()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(rates.ratePerEur()).containsEntry("USD", new BigDecimal("1.0812")).hasSize(2);
    }
}
