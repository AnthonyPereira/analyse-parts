package com.analyseparts.ingestion.esef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FilingsXbrlClientTest {

    private static final String INDEX_URL =
            "https://xbrl.test/api/filings?filter=%5B%7B%22name%22%3A%22entity.identifier%22%2C%22op%22%3A%22eq%22%2C%22val%22%3A%22LEI1%22%7D%5D&page%5Bsize%5D=100";

    private MockRestServiceServer server;
    private FilingsXbrlClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // pas d'attente dans les tests : 2 nouveaux essais immédiats
        client = new FilingsXbrlClient(builder, "https://xbrl.test", Duration.ZERO, List.of(Duration.ZERO, Duration.ZERO));
    }

    @Test
    void retriesAfterTooManyRequests() {
        server.expect(ExpectedCount.once(), requestTo(INDEX_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(ExpectedCount.once(), requestTo(INDEX_URL)).andRespond(withSuccess("""
                {"data":[{"type":"filing","id":"1","attributes":{"fxo_id":"LEI1-2025-12-31-ESEF-FR-0",
                  "period_end":"2025-12-31","country":"FR","json_url":"/x.json","error_count":0}}]}
                """, MediaType.valueOf("application/vnd.api+json")));

        var filings = client.listFilings("LEI1");

        assertThat(filings).extracting(FilingsXbrlClient.FilingInfo::fxoId).containsExactly("LEI1-2025-12-31-ESEF-FR-0");
        server.verify();
    }

    @Test
    void downloadsReportAtTheAddressGivenByTheIndex() {
        // dossier, nom et extension ne se déduisent pas de l'adresse du xBRL-JSON
        server.expect(ExpectedCount.once(), requestTo(INDEX_URL)).andRespond(withSuccess("""
                {"data":[{"type":"filing","id":"1","attributes":{"fxo_id":"a","period_end":"2025-12-31",
                  "json_url":"/LEI1/2025-12-31/ESEF/FR/1/bnp-2025-12-31-1-fr.json",
                  "report_url":"/LEI1/2025-12-31/ESEF/FR/1/bnp-2025-12-31-1/reports/bnp-2025-12-31-1-fr.html"}}]}
                """, MediaType.valueOf("application/vnd.api+json")));
        server.expect(ExpectedCount.once(),
                        requestTo("https://xbrl.test/LEI1/2025-12-31/ESEF/FR/1/bnp-2025-12-31-1/reports/bnp-2025-12-31-1-fr.html"))
                .andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));

        byte[] report = client.downloadReport("/LEI1/2025-12-31/ESEF/FR/1/bnp-2025-12-31-1-fr.json");

        assertThat(new String(report)).isEqualTo("<html/>");
        server.verify();
    }

    @Test
    void guessesReportAddressWhenIndexDoesNotKnowTheFiling() {
        server.expect(ExpectedCount.once(), requestTo(INDEX_URL)).andRespond(withSuccess("""
                {"data":[]}
                """, MediaType.valueOf("application/vnd.api+json")));

        assertThat(client.resolveReportUrl("/LEI1/2025-12-31/ESEF/FR/0/x-fr.json"))
                .isEqualTo("/LEI1/2025-12-31/ESEF/FR/0/x-fr/reports/x-fr.xhtml");
    }

    @Test
    void givesUpAfterLastRetry() {
        server.expect(ExpectedCount.times(3), requestTo(INDEX_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.listFilings("LEI1")).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        server.verify();
    }
}
