package com.analyseparts.ingestion;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.SourceHealth.Source;
import com.analyseparts.ingestion.SourceHealth.State;
import com.analyseparts.ingestion.prices.QuoteRefreshService;
import com.analyseparts.ingestion.prices.YahooQuoteClient;
import com.analyseparts.ingestion.prices.YahooQuoteClient.SparkBatch;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le scénario qui motivait tout : Yahoo répond normalement, mais a renommé un champ. Avant, on
 * obtenait zéro cours sans aucune erreur ; maintenant la source passe en panne, et ça se voit.
 */
@SpringBootTest(properties = {"analyse-parts.ingestion.auto-lei=false", "analyse-parts.amf.enabled=false",
        "analyse-parts.score-history.backfill-on-startup=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SourceHealthIntegrationTest {

    @MockitoBean
    YahooQuoteClient yahoo;
    @Autowired
    QuoteRefreshService refresh;
    @Autowired
    CompanyRepository companies;
    @Autowired
    SourceHealth health;
    @Autowired
    MockMvc http;

    @Test
    void aYahooFormatChangeIsDetectedAndShown() throws Exception {
        companies.save(new Company("FR0000000111", "FORMAT SA", "FMT", "FR", "Euronext Paris"));
        // Yahoo renvoie bien un résultat par symbole… dont aucun n'a plus de prix lisible
        when(yahoo.spark(anyList())).thenAnswer(call ->
                new SparkBatch(((List<?>) call.getArgument(0)).size(), List.of()));

        var result = refresh.refreshQuotes();

        assertThat(result.recorded()).isZero();
        var status = health.of(Source.YAHOO_QUOTES);
        assertThat(status.state()).isEqualTo(State.DOWN);
        assertThat(status.problem()).contains("format a probablement changé");

        http.perform(get("/actuator/health/sources"))
                .andExpect(status().isOk()) // DEGRADED reste en HTTP 200
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.details.YAHOO_QUOTES").value(Matchers.startsWith("DOWN")));
        http.perform(get("/api/sources"))
                .andExpect(jsonPath("$[0].source").value("YAHOO_QUOTES"))
                .andExpect(jsonPath("$[0].state").value("DOWN"));
    }
}
