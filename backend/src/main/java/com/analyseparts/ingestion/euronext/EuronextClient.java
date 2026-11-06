package com.analyseparts.ingestion.euronext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Télécharge la liste publique des actions d'une place Euronext (bouton "Download" du site). */
@Component
public class EuronextClient {

    private final RestClient restClient;

    public EuronextClient(RestClient.Builder builder,
                          @Value("${analyse-parts.euronext.base-url:https://live.euronext.com}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("User-Agent", "analyse-parts/0.1")
                .build();
    }

    /** @param mic code de la place de cotation, ex. XPAR = Euronext Paris */
    public List<ListedEquity> fetchEquities(String mic) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("args[fe_type]", "csv");
        form.add("args[fe_decimal]", ".");
        form.add("args[fe_date_format]", "d/m/Y");
        form.add("iDisplayLength", "5000");
        form.add("iDisplayStart", "0");

        String csv = restClient.post()
                .uri("/pd_es/data/stocks/download?mics={mic}", mic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        return EuronextEquityCsvParser.parse(csv == null ? "" : csv);
    }
}
