package com.analyseparts.ingestion.esef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les actions du marché principal de Nasdaq Helsinki, Stockholm et Copenhague, avec leur code ISIN
 * (ADR 0020).
 * <p>
 * GLEIF ne relie aucun ISIN aux LEI des sociétés finlandaises, et à une partie des suédoises et des
 * danoises : sans ISIN, la recherche de Yahoo ne trouve pas leur action. On cherche alors le nom de
 * l'émetteur dans la liste de Nasdaq Nordic de son pays. Liste lue une fois par place et par
 * démarrage de l'application (quelques centaines de lignes).
 */
@Component
public class NasdaqNordicClient {

    /** Place Nasdaq du pays du siège. */
    static final Map<String, String> MARKET_BY_COUNTRY = Map.of("FI", "HEL", "SE", "STO", "DK", "CPH");

    /** Formes juridiques retirées des noms avant comparaison (« Elisa Oyj » = « Elisa »). */
    private static final Set<String> LEGAL_FORMS = Set.of("oyj", "abp", "oy", "ab", "publ", "a/s", "as", "asa",
            "plc", "ltd", "sa", "se", "nv", "a", "b", "c", "1", "2", "ser", "series"); // dernières : classes d'actions

    public record Share(String name, String symbol, String isin, double turnover) {
    }

    private final RestClient restClient;
    private final Map<String, List<Share>> cache = new ConcurrentHashMap<>();

    public NasdaqNordicClient(RestClient.Builder builder,
                              @Value("${analyse-parts.nasdaq-nordic.base-url:https://api.nasdaq.com}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** Le code ISIN de l'action d'un émetteur, d'après son nom ; vide hors pays nordiques ou sans nom concordant. */
    public Optional<String> findIsin(String issuerName, String country) {
        String market = MARKET_BY_COUNTRY.get(country);
        if (market == null || issuerName == null) {
            return Optional.empty();
        }
        return findIsin(cache.computeIfAbsent(market, this::listShares), issuerName);
    }

    /** Le nom normalisé doit être identique ; entre deux classes d'actions (A et B), la plus échangée. */
    static Optional<String> findIsin(List<Share> shares, String issuerName) {
        String wanted = normalize(issuerName);
        return shares.stream()
                .filter(s -> s.isin() != null && normalize(s.name()).equals(wanted))
                .max(Comparator.comparingDouble(Share::turnover))
                .map(Share::isin);
    }

    static String normalize(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("(publ)", " ")
                .replaceAll("[^a-z0-9/& ]", " ");
        StringBuilder out = new StringBuilder();
        for (String word : plain.trim().split("\\s+")) {
            if (!LEGAL_FORMS.contains(word)) {
                out.append(out.isEmpty() ? "" : " ").append(word);
            }
        }
        return out.toString();
    }

    private List<Share> listShares(String market) {
        Response response = restClient.get()
                .uri("/api/nordic/screener/shares?category=MAIN_MARKET&tableonly=false&market={m}", market)
                .retrieve()
                .body(Response.class);
        if (response == null || response.data() == null || response.data().instrumentListing() == null
                || response.data().instrumentListing().rows() == null) {
            return List.of();
        }
        return response.data().instrumentListing().rows().stream()
                .map(r -> new Share(r.fullName(), r.symbol(), r.isin(), parseTurnover(r.turnover())))
                .toList();
    }

    private static double parseTurnover(String turnover) {
        try {
            return turnover == null || turnover.isBlank() ? 0 : Double.parseDouble(turnover.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(Listing instrumentListing) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Listing(List<Row> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(String fullName, String symbol, String isin, String turnover) {
    }
}
