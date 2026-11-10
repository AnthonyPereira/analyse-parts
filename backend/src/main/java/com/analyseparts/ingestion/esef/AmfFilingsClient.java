package com.analyseparts.ingestion.esef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Rapports annuels ESEF déposés auprès de l'AMF, via l'open data de la DILA
 * (info-financiere.gouv.fr, jeu de données {@code flux-amf-new-prod}).
 * <p>
 * C'est le <b>mécanisme officiel de stockage</b> des informations réglementées des sociétés
 * cotées en France : il reçoit les rapports le jour de leur publication, alors que
 * filings.xbrl.org (qui les recopie) a parfois des mois de retard. Les rapports ESEF y sont des
 * paquets ZIP ({@code .zip} ou {@code .xbri}) déposés comme « Document de référence »
 * (document d'enregistrement universel) ou « Rapports financiers et d'audit annuels ».
 */
@Component
public class AmfFilingsClient {

    private static final String ANNUAL_TYPES = "(sous_type_d_information=\"Document de référence\""
            + " or sous_type_d_information=\"Rapports financiers et d'audit annuels\")";
    private static final String ESEF_FILES = "(fichierdecontenu_inf_fic_nom like \"*.zip\""
            + " or fichierdecontenu_inf_fic_nom like \"*.xbri\")";

    private final RestClient http;

    public AmfFilingsClient(RestClient.Builder builder,
                            @Value("${analyse-parts.amf.base-url:https://www.info-financiere.gouv.fr}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    /**
     * Un rapport déposé.
     *
     * @param fileName chemin du fichier chez la DILA, unique : sert d'identifiant
     */
    public record AmfFiling(LocalDate publishedOn, String title, String fileName, String url, String lei) {
    }

    /** Les rapports annuels ESEF d'une société publiés depuis {@code since}, du plus récent au plus ancien. */
    public List<AmfFiling> findAnnualReports(String isin, String lei, LocalDate since) {
        return findFilings(isin, lei, ANNUAL_TYPES + " and " + ESEF_FILES, since);
    }

    private static final String HALF_YEAR_TYPE =
            "sous_type_d_information=\"Rapports financiers et d'audit semestriels/examens réduits\"";

    /**
     * Les dépôts « rapports financiers semestriels » d'une société (des PDF), du plus récent au plus
     * ancien. On y trouve aussi les avis de mise à disposition et la version anglaise : au lecteur
     * de choisir celui qui contient les comptes.
     */
    public List<AmfFiling> findHalfYearReports(String isin, String lei, LocalDate since) {
        return findFilings(isin, lei, HALF_YEAR_TYPE + " and fichierdecontenu_inf_fic_nom like \"*.pdf\"", since);
    }

    private List<AmfFiling> findFilings(String isin, String lei, String what, LocalDate since) {
        String who = lei == null ? "identificationsociete_iso_cd_isi=\"" + isin + "\""
                : "(identificationsociete_iso_cd_isi=\"" + isin + "\" or identificationsociete_iso_cd_lei=\"" + lei + "\")";
        String where = who + " and " + what + " and informationdeposee_inf_dat_emt>=\"" + since + "\"";
        Response response = http.get()
                .uri(b -> b.path("/api/explore/v2.1/catalog/datasets/flux-amf-new-prod/records")
                        .queryParam("where", "{where}")
                        .queryParam("order_by", "informationdeposee_inf_dat_emt desc")
                        .queryParam("limit", 50)
                        .build(where))
                .retrieve()
                .body(Response.class);
        if (response == null || response.results() == null) {
            return List.of();
        }
        return response.results().stream()
                .filter(r -> r.url() != null && r.fileName() != null && r.publishedAt() != null)
                .map(r -> new AmfFiling(OffsetDateTime.parse(r.publishedAt()).toLocalDate(), r.title(), r.fileName(),
                        r.url(), r.lei()))
                .toList();
    }

    /** Télécharge un paquet ESEF (quelques Mo à quelques dizaines de Mo). */
    public byte[] download(String url) {
        return http.get().uri(URI.create(url)).retrieve().body(byte[].class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Record> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Record(@JsonProperty("informationdeposee_inf_dat_emt") String publishedAt,
                  @JsonProperty("informationdeposee_inf_tit_inf") String title,
                  @JsonProperty("fichierdecontenu_inf_fic_nom") String fileName,
                  @JsonProperty("url_de_recuperation") String url,
                  @JsonProperty("identificationsociete_iso_cd_lei") String lei) {
    }
}
