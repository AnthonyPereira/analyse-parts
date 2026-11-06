package com.analyseparts.ingestion;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.euronext.EuronextImportService;
import com.analyseparts.ingestion.euronext.ListedEquity;
import com.analyseparts.ingestion.gleif.GleifClient;
import com.analyseparts.ingestion.gleif.GleifClient.LeiRecord;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "analyse-parts.gleif.pause=0s")
@Import(TestcontainersConfiguration.class)
class IngestionIntegrationTest {

    @Autowired
    EuronextImportService importService;

    @Autowired
    LeiEnrichmentService enrichmentService;

    @Autowired
    CompanyRepository companies;

    @MockitoBean
    GleifClient gleif;

    @BeforeEach
    void clean() {
        companies.deleteAll();
    }

    @Test
    void importCreatesThenUpdatesByIsin() {
        var first = importService.importEquities(List.of(
                new ListedEquity("ACCOR", "FR0000120404", "AC", "Euronext Paris"),
                new ListedEquity("AIRBUS", "NL0000235190", "AIR", "Euronext Paris")));
        var second = importService.importEquities(List.of(
                new ListedEquity("ACCOR SA", "FR0000120404", "AC", "Euronext Paris")));

        assertThat(first).isEqualTo(new EuronextImportService.ImportResult(2, 2, 0));
        assertThat(second).isEqualTo(new EuronextImportService.ImportResult(1, 0, 1));
        assertThat(companies.findByIsin("FR0000120404")).get().extracting(Company::getName).isEqualTo("ACCOR SA");
        assertThat(companies.findByIsin("NL0000235190")).get().extracting(Company::getCountry).isEqualTo("NL");
    }

    @Test
    void enrichmentSetsLeiOnceAndSkipsAlreadyChecked() {
        importService.importEquities(List.of(
                new ListedEquity("TOTALENERGIES", "FR0000120271", "TTE", "Euronext Paris"),
                new ListedEquity("INCONNUE", "FR0000000001", "XXX", "Euronext Paris")));
        when(gleif.findByIsin(anyString())).thenReturn(Optional.empty());
        when(gleif.findByIsin("FR0000120271"))
                .thenReturn(Optional.of(new LeiRecord("529900S21EQ1BO4ESM68", "TotalEnergies SE", "FR")));

        var first = enrichmentService.enrichMissingLeis();
        var second = enrichmentService.enrichMissingLeis();

        assertThat(first).isEqualTo(new LeiEnrichmentService.EnrichmentResult(2, 1, 1, 0, 0));
        assertThat(second.checked()).isZero(); // déjà vérifiées : pas de nouvel appel GLEIF
        assertThat(companies.findByIsin("FR0000120271")).get()
                .extracting(Company::getLei, Company::getLegalName)
                .containsExactly("529900S21EQ1BO4ESM68", "TotalEnergies SE");
    }

    @Test
    void secondShareClassWithSameLeiIsCountedAsDuplicate() {
        importService.importEquities(List.of(
                new ListedEquity("ACTION A", "FR0000000011", "AAA", "Euronext Paris"),
                new ListedEquity("ACTION B", "FR0000000029", "BBB", "Euronext Paris")));
        when(gleif.findByIsin(anyString()))
                .thenReturn(Optional.of(new LeiRecord("969500ABCDEFGHIJKL12", "Même Société SA", "FR")));

        var result = enrichmentService.enrichMissingLeis();

        assertThat(result.found()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
    }
}
