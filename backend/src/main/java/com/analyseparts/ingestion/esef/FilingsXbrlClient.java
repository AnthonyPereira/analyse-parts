package com.analyseparts.ingestion.esef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Client de filings.xbrl.org (XBRL International) : index des rapports ESEF déposés en Europe,
 * et chaque rapport converti au format xBRL-JSON.
 * <p>
 * ESEF (European Single Electronic Format) : depuis 2021, les sociétés cotées de l'UE publient
 * leur rapport annuel en XHTML avec des balises XBRL. Chaque chiffre des états financiers y est
 * étiqueté avec un concept de la taxonomie IFRS, ce qui le rend lisible par une machine.
 */
@Component
public class FilingsXbrlClient {

    private final RestClient restClient;

    public FilingsXbrlClient(RestClient.Builder builder,
                             @Value("${analyse-parts.esef.base-url:https://filings.xbrl.org}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("User-Agent", "analyse-parts/0.1")
                .build();
    }

    /** Une entrée de l'index. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilingInfo(
            @JsonProperty("fxo_id") String fxoId,
            @JsonProperty("period_end") String periodEnd,
            String country,
            @JsonProperty("json_url") String jsonUrl,
            @JsonProperty("error_count") Integer errorCount,
            @JsonProperty("date_added") String dateAdded) {
    }

    /** Tous les rapports d'une entité, identifiée par son LEI. */
    public List<FilingInfo> listFilings(String lei) {
        String filter = "[{\"name\":\"entity.identifier\",\"op\":\"eq\",\"val\":\"" + lei + "\"}]";
        IndexResponse response = restClient.get()
                .uri(b -> b.path("/api/filings")
                        .queryParam("filter", "{filter}")
                        .queryParam("page[size]", "100")
                        .build(filter))
                .retrieve()
                .body(IndexResponse.class);
        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream().map(IndexItem::attributes).toList();
    }

    /** Télécharge un rapport au format xBRL-JSON (souvent 5 à 25 Mo, à cause des annexes en texte). */
    public XbrlJson download(String jsonUrl) {
        return restClient.get().uri(jsonUrl).retrieve().body(XbrlJson.class);
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
