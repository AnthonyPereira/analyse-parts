package com.analyseparts.ingestion.esef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client de filings.xbrl.org (XBRL International) : index des rapports ESEF déposés en Europe,
 * et chaque rapport converti au format xBRL-JSON.
 * <p>
 * ESEF (European Single Electronic Format) : depuis 2021, les sociétés cotées de l'UE publient
 * leur rapport annuel en XHTML avec des balises XBRL. Chaque chiffre des états financiers y est
 * étiqueté avec un concept de la taxonomie IFRS, ce qui le rend lisible par une machine.
 * <p>
 * Service gratuit et limité en débit (réponse 429 sans indication de délai) : on espace les
 * requêtes et on réessaie après une attente croissante en cas de refus.
 */
@Component
public class FilingsXbrlClient {

    private static final Logger log = LoggerFactory.getLogger(FilingsXbrlClient.class);

    private final RestClient restClient;
    private final Duration minInterval;
    private final List<Duration> backoff;
    private long lastRequestAt;

    public FilingsXbrlClient(RestClient.Builder builder,
                             @Value("${analyse-parts.esef.base-url:https://filings.xbrl.org}") String baseUrl,
                             @Value("${analyse-parts.esef.min-interval:2s}") Duration minInterval,
                             @Value("${analyse-parts.esef.backoff:30s,60s,120s}") List<Duration> backoff) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("User-Agent", "analyse-parts/0.1")
                .build();
        this.minInterval = minInterval;
        this.backoff = backoff;
    }

    /** Une entrée de l'index. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilingInfo(
            @JsonProperty("fxo_id") String fxoId,
            @JsonProperty("period_end") String periodEnd,
            String country,
            @JsonProperty("json_url") String jsonUrl,
            @JsonProperty("error_count") Integer errorCount,
            @JsonProperty("date_added") String dateAdded,
            @JsonProperty("report_url") String reportUrl) {

        public FilingInfo(String fxoId, String periodEnd, String country, String jsonUrl, Integer errorCount,
                          String dateAdded) {
            this(fxoId, periodEnd, country, jsonUrl, errorCount, dateAdded, null);
        }
    }

    /** Tous les rapports d'une entité, identifiée par son LEI. */
    public List<FilingInfo> listFilings(String lei) {
        String filter = "[{\"name\":\"entity.identifier\",\"op\":\"eq\",\"val\":\"" + lei + "\"}]";
        IndexResponse response = call(() -> restClient.get()
                .uri(b -> b.path("/api/filings")
                        .queryParam("filter", "{filter}")
                        .queryParam("page[size]", "100")
                        .build(filter))
                .retrieve()
                .body(IndexResponse.class));
        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream().map(IndexItem::attributes).toList();
    }

    /** Télécharge un rapport au format xBRL-JSON (souvent 5 à 25 Mo, à cause des annexes en texte). */
    public XbrlJson download(String jsonUrl) {
        return call(() -> restClient.get().uri(jsonUrl).retrieve().body(XbrlJson.class));
    }

    /**
     * Le rapport XHTML lisible d'un dépôt, à partir de l'adresse de son xBRL-JSON.
     * <p>
     * Son adresse ne se devine pas : le dossier, le nom et l'extension (.xhtml ou .html) varient
     * d'un dépôt à l'autre ({@code …/bnp-2025-12-31-1/reports/bnp-2025-12-31-1-fr.html}). On la
     * demande donc à l'index ({@code report_url}), retrouvé par le LEI, premier segment de l'adresse.
     * L'ancienne devinette ({@code …/nom/reports/nom.xhtml}) ne sert que si l'index ne connaît pas le dépôt.
     */
    public byte[] downloadReport(String jsonUrl) {
        String reportUrl = reportUrlOf(jsonUrl);
        return call(() -> restClient.get().uri(reportUrl).retrieve().body(byte[].class));
    }

    String reportUrlOf(String jsonUrl) {
        String lei = jsonUrl.substring(1, jsonUrl.indexOf('/', 1));
        return listFilings(lei).stream()
                .filter(f -> jsonUrl.equals(f.jsonUrl()) && f.reportUrl() != null)
                .map(FilingInfo::reportUrl)
                .findFirst()
                .orElseGet(() -> {
                    String base = jsonUrl.substring(0, jsonUrl.length() - ".json".length());
                    String name = base.substring(base.lastIndexOf('/') + 1);
                    return base + "/reports/" + name + ".xhtml";
                });
    }

    /** Espace les requêtes et réessaie sur "429 Too Many Requests". */
    private synchronized <T> T call(Supplier<T> request) {
        for (int attempt = 0; ; attempt++) {
            waitForSlot();
            try {
                return request.get();
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= backoff.size()) {
                    throw e;
                }
                Duration wait = backoff.get(attempt);
                log.warn("filings.xbrl.org : trop de requêtes, nouvel essai dans {} s", wait.toSeconds());
                sleep(wait);
            }
        }
    }

    private void waitForSlot() {
        long elapsed = System.nanoTime() - lastRequestAt;
        long remaining = minInterval.toNanos() - elapsed;
        if (lastRequestAt != 0 && remaining > 0) {
            sleep(Duration.ofNanos(remaining));
        }
        lastRequestAt = System.nanoTime();
    }

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant l'attente", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexResponse(List<IndexItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexItem(FilingInfo attributes) {
    }

    /** Structure xBRL-JSON (seule la partie "facts" nous intéresse). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record XbrlJson(Map<String, XbrlFact> facts) {
    }

    /**
     * Un fait xBRL-JSON. {@code dimensions} contient toujours {@code concept} et {@code period},
     * plus {@code unit} pour un nombre, et éventuellement des axes (ex. {@code ifrs-full:SegmentsAxis}).
     * {@code decimals} vaut un entier ou "INF" : on le garde en Object.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record XbrlFact(String value, Object decimals, Map<String, String> dimensions) {
    }
}
