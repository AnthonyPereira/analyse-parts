package com.analyseparts.ingestion.prices;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
                            "regularMarketPrice":403.75,"regularMarketTime":1793973274}}]},
                          {"symbol":"AIR.PA","response":[{"meta":{"currency":"EUR","symbol":"AIR.PA","exchangeName":"PAR",
                            "regularMarketPrice":196.54,"regularMarketTime":1793973278}}]}]}}
                        """, MediaType.APPLICATION_JSON));

        var quotes = new YahooQuoteClient(builder, "https://yahoo.test").quotes(List.of("MC.PA", "AIR.PA"));

        assertThat(quotes).hasSize(2);
        assertThat(quotes.getFirst()).isEqualTo(new YahooQuoteClient.YahooQuote("MC.PA", new BigDecimal("403.75"),
                "EUR", "PAR", Instant.ofEpochSecond(1793973274)));
        server.verify();
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
