package com.analyseparts.ingestion.euronext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Blocs de la fiche d'une action sur live.euronext.com (pages publiques, en HTML).
 * Le site n'a pas d'API documentée : on reste sur un rythme modeste (une société par seconde,
 * une fois par mois).
 */
@Component
public class EuronextFactsheetClient {

    private final RestClient http;

    public EuronextFactsheetClient(RestClient.Builder builder,
                                   @Value("${analyse-parts.euronext.base-url:https://live.euronext.com}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; analyse-parts/0.1)")
                .build();
    }

    /** {@code block} : fs_icb_block (secteur) ou fs_tradinginfo_block (nombre d'actions). */
    public String fetchBlock(String isin, String mic, String block) {
        return http.get()
                .uri("/en/ajax/getFactsheetInfoBlock/STOCK/{isin}-{mic}/{block}", isin, mic, block)
                .retrieve()
                .body(String.class);
    }
}
