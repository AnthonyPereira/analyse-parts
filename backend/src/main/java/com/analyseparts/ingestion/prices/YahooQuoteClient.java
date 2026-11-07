package com.analyseparts.ingestion.prices;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cours via l'API publique (non documentée) de Yahoo Finance.
 * <ul>
 *   <li>« spark » : le dernier cours de {@value #MAX_SYMBOLS} titres par requête, avec environ
 *       15 minutes de différé pour Euronext Paris ;</li>
 *   <li>« chart » : l'historique d'un titre (clôtures quotidiennes) ou la séance du jour
 *       (un point toutes les 5 minutes).</li>
 * </ul>
 * Les symboles Yahoo sont le mnémonique Euronext suivi du suffixe de la place : {@code MC.PA}
 * pour LVMH à Paris. Ce n'est pas une source officielle : elle peut changer ou disparaître. Le
 * cours de clôture Euronext (liste des actions, importée chaque jour) sert de solution de repli.
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
     * @param exchange      code de place Yahoo (PAR pour Paris), pour vérifier qu'on a le bon titre
     * @param previousClose clôture de la séance précédente
     */
    public record YahooQuote(String symbol, BigDecimal price, String currency, String exchange, Instant marketTime,
                             BigDecimal previousClose) {
    }

    /** Une série de cours : {@code time} en secondes Unix, {@code close} et {@code volume} parfois absents. */
    public record YahooChart(String symbol, String currency, String exchange, BigDecimal previousClose,
                             List<Point> points) {

        public record Point(Instant time, BigDecimal close, Long volume) {
        }
    }

    public List<YahooQuote> quotes(List<String> symbols) {
        return spark(symbols).usable();
    }

    /**
     * Ce que Yahoo a renvoyé pour un lot de symboles.
     *
     * @param results nombre de résultats dans la réponse, exploitables ou non
     * @param usable  les cours complets (prix et heure présents)
     */
    public record SparkBatch(int results, List<YahooQuote> usable) {

        /**
         * Des résultats, mais aucun exploitable : le plus souvent, Yahoo a renommé ou déplacé un
         * champ. Sans ce contrôle, on obtiendrait « zéro cours » sans la moindre erreur.
         */
        public boolean looksLikeFormatChange() {
            return results > 0 && usable.isEmpty();
        }
    }

    public SparkBatch spark(List<String> symbols) {
        if (symbols.size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("Au plus " + MAX_SYMBOLS + " symboles par requête");
        }
        SparkResponse response = http.get()
                .uri("/v7/finance/spark?symbols={symbols}&range=1d&interval=1d", String.join(",", symbols))
                .retrieve()
                .body(SparkResponse.class);
        if (response == null || response.spark() == null || response.spark().result() == null) {
            return new SparkBatch(0, List.of());
        }
        List<YahooQuote> usable = response.spark().result().stream()
                .filter(r -> r.response() != null && !r.response().isEmpty())
                .map(r -> r.response().getFirst().meta())
                .filter(m -> m != null && m.regularMarketPrice() != null && m.regularMarketTime() != null)
                .map(m -> new YahooQuote(m.symbol(), m.regularMarketPrice(), m.currency(), m.exchangeName(),
                        Instant.ofEpochSecond(m.regularMarketTime()), m.previousCloseOrChart()))
                .toList();
        return new SparkBatch(response.spark().result().size(), usable);
    }

    /**
     * @param range    période Yahoo : 1d, 5d, 1mo, 1y, 5y, max…
     * @param interval pas : 5m pour la séance, 1d pour l'historique
     */
    public Optional<YahooChart> chart(String symbol, String range, String interval) {
        ChartResponse response = http.get()
                .uri("/v8/finance/chart/{symbol}?range={range}&interval={interval}", symbol, range, interval)
                .retrieve()
                .body(ChartResponse.class);
        if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
            return Optional.empty();
        }
        ChartResult r = response.chart().result().getFirst();
        List<YahooChart.Point> points = new ArrayList<>();
        if (r.timestamp() != null && r.indicators() != null && r.indicators().quote() != null
                && !r.indicators().quote().isEmpty()) {
            Indicator q = r.indicators().quote().getFirst();
            for (int i = 0; i < r.timestamp().size(); i++) {
                BigDecimal close = at(q.close(), i);
                if (close != null) { // pas d'échange sur ce créneau
                    Long volume = at(q.volume(), i);
                    points.add(new YahooChart.Point(Instant.ofEpochSecond(r.timestamp().get(i)), close, volume));
                }
            }
        }
        Meta m = r.meta();
        if (m == null) {
            // la réponse ne ressemble plus à ce qu'on attend : une erreur claire plutôt qu'un NullPointerException
            throw new IllegalStateException("réponse Yahoo inattendue pour " + symbol + " (bloc meta absent)");
        }
        return Optional.of(new YahooChart(m.symbol(), m.currency(), m.exchangeName(), m.previousCloseOrChart(), points));
    }

    private static <T> T at(List<T> values, int i) {
        return values != null && i < values.size() ? values.get(i) : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SparkResponse(Spark spark) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Spark(List<SparkResult> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SparkResult(String symbol, List<ChartResult> response) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResponse(Chart chart) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(List<ChartResult> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResult(Meta meta, List<Long> timestamp, Indicators indicators) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Indicators(List<Indicator> quote) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Indicator(List<BigDecimal> close, List<Long> volume) {
    }

    /**
     * {@code previousClose} : clôture de la veille (sur une période d'un jour) ;
     * {@code chartPreviousClose} : clôture avant le début de la période demandée.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(String symbol, String currency, String exchangeName, BigDecimal regularMarketPrice,
                Long regularMarketTime, BigDecimal previousClose, BigDecimal chartPreviousClose) {

        BigDecimal previousCloseOrChart() {
            return previousClose != null ? previousClose : chartPreviousClose;
        }
    }
}
