package com.analyseparts.ingestion.gleif;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Client de l'API publique GLEIF (Global Legal Entity Identifier Foundation).
 * GLEIF tient le registre mondial des LEI et publie la correspondance ISIN → LEI.
 * Limite d'usage : 60 requêtes par minute.
 */
@Component
public class GleifClient {

    private final RestClient restClient;

    public GleifClient(RestClient.Builder builder,
                       @Value("${analyse-parts.gleif.base-url:https://api.gleif.org/api/v1}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.api+json")
                .build();
    }

    public record LeiRecord(String lei, String legalName, String country) {
    }

    public Optional<LeiRecord> findByIsin(String isin) {
        Response response = restClient.get()
                .uri("/lei-records?filter[isin]={isin}", isin)
                .accept(MediaType.valueOf("application/vnd.api+json"))
                .retrieve()
                .body(Response.class);
        if (response == null || response.data() == null) {
            return Optional.empty();
        }
        // Normalement un seul résultat ; on préfère une entité active s'il y en a plusieurs.
        return response.data().stream()
                .sorted((a, b) -> Boolean.compare(b.isActive(), a.isActive()))
                .findFirst()
                .map(r -> new LeiRecord(r.id(),
                        r.attributes().entity().legalName().name(),
                        r.attributes().entity().legalAddress().country()));
    }

    // --- Structure JSON:API renvoyée par GLEIF (seuls les champs utiles sont mappés) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Item> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String id, Attributes attributes) {
        boolean isActive() {
            return "ACTIVE".equals(attributes.entity().status());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Attributes(Entity entity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entity(Name legalName, Address legalAddress, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Name(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Address(String country) {
    }
}
