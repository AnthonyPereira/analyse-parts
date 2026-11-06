package com.analyseparts.ingestion.prices;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cours en séance via l'API publique (non documentée) de Yahoo Finance.
 * <p>
 * Le point d'accès « spark » renvoie jusqu'à {@value #MAX_SYMBOLS} cours par requête, avec
 * environ 15 minutes de différé pour Euronext Paris. Les symboles Yahoo sont le mnémonique
 * Euronext suivi du suffixe de la place : {@code MC.PA} pour LVMH à Paris.
 * <p>
 * Ce n'est pas une source officielle : elle peut changer ou disparaître. Le cours de clôture
 * Euronext (liste des actions, importée chaque jour) sert de solution de repli.
 */
@Component
public class YahooQuoteClient {

    static final int MAX_SYMBOLS = 20;

    private final RestClient http;

    public YahooQuoteClient(RestClient.Builder builder,
                            @Value("${analyse-parts.yahoo.base-url:https://query1.finance.yahoo.com}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; analyse-parts/0.1)")
                .build();
    }

    /**
     * @param exchange code de place Yahoo (PAR pour Paris), pour vérifier qu'on a le bon titre
     */
    public record YahooQuote(String symbol, BigDecimal price, String currency, String exchange, Instant marketTime) {
    }

    public List<YahooQuote> quotes(List<String> symbols) {
        if (symbols.size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("Au plus " + MAX_SYMBOLS + " symboles par requête");
        }
        Response response = http.get()
                .uri("/v7/finance/spark?symbols={symbols}&range=1d&interval=1d", String.join(",", symbols))
                .retrieve()
                .body(Response.class);
        if (response == null || response.spark() == null || response.spark().result() == null) {
            return List.of();
        }
        return response.spark().result().stream()
                .filter(r -> r.response() != null && !r.response().isEmpty())
                .map(r -> r.response().getFirst().meta())
                .filter(m -> m != null && m.regularMarketPrice() != null && m.regularMarketTime() != null)
                .map(m -> new YahooQuote(m.symbol(), m.regularMarketPrice(), m.currency(), m.exchangeName(),
                        Instant.ofEpochSecond(m.regularMarketTime())))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(Spark spark) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Spark(List<Result> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(String symbol, List<Chart> response) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(Meta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(String symbol, String currency, String exchangeName, BigDecimal regularMarketPrice,
                Long regularMarketTime) {
    }
}
