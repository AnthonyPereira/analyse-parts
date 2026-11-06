package com.analyseparts.ingestion;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.euronext.EuronextImportService;
import com.analyseparts.ingestion.euronext.ListedEquity;
import com.analyseparts.ingestion.gleif.GleifClient;
import com.analyseparts.ingestion.gleif.GleifClient.LeiRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Chaîne complète par événement : l'import publie CompaniesImported, et la recherche
 * de LEI démarre seule, en arrière-plan, après le commit.
 */
@SpringBootTest(properties = "analyse-parts.gleif.pause=0s")
@Import(TestcontainersConfiguration.class)
class CompaniesImportedFlowTest {

    @Autowired
    EuronextImportService importService;

    @Autowired
    CompanyRepository companies;

    @Autowired
    EventPublicationRegistry registry;

    @MockitoBean
    GleifClient gleif;

    @Test
    void importTriggersLeiEnrichmentAndCompletesThePublication() {
        when(gleif.findByIsin("FR0000121014"))
                .thenReturn(Optional.of(new LeiRecord("IOG4E947OATN0KJYSD45", "LVMH MOET HENNESSY LOUIS VUITTON", "FR")));

        importService.importEquities("XPAR", List.of(
                new ListedEquity("LVMH", "FR0000121014", "MC", "Euronext Paris")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(companies.findByIsin("FR0000121014")).get()
                    .extracting(Company::getLei).isEqualTo("IOG4E947OATN0KJYSD45");
            // L'événement est marqué terminé dans le registre Modulith
            assertThat(registry.findIncompletePublications()).isEmpty();
        });
    }
}
