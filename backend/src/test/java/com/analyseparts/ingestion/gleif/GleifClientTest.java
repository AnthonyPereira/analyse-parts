package com.analyseparts.ingestion.gleif;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GleifClientTest {

    private static final MediaType JSON_API = MediaType.valueOf("application/vnd.api+json");

    private MockRestServiceServer server;
    private GleifClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GleifClient(builder, "https://gleif.test");
    }

    @Test
    void mapsLeiLegalNameAndCountry() {
        // Extrait (raccourci) d'une vraie réponse GLEIF pour TotalEnergies
        server.expect(requestTo("https://gleif.test/lei-records?filter%5Bisin%5D=FR0000120271"))
                .andRespond(withSuccess("""
                        {"data":[{"type":"lei-records","id":"529900S21EQ1BO4ESM68",
                          "attributes":{"lei":"529900S21EQ1BO4ESM68",
                            "entity":{"legalName":{"name":"TotalEnergies SE","language":"fr"},
                                      "legalAddress":{"country":"FR","city":"Courbevoie"},
                                      "status":"ACTIVE"}}}]}
                        """, JSON_API));

        var record = client.findByIsin("FR0000120271");

        assertThat(record).contains(new GleifClient.LeiRecord("529900S21EQ1BO4ESM68", "TotalEnergies SE", "FR"));
        server.verify();
    }

    @Test
    void emptyWhenNoRecord() {
        server.expect(requestTo("https://gleif.test/lei-records?filter%5Bisin%5D=FR0000000000"))
                .andRespond(withSuccess("{\"data\":[]}", JSON_API));

        assertThat(client.findByIsin("FR0000000000")).isEmpty();
    }
}
