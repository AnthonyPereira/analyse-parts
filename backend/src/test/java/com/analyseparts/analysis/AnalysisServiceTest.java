package com.analyseparts.analysis;

import com.analyseparts.analysis.internal.AnalysisRepository;
import com.analyseparts.analysis.internal.ReportAnalysisRunner;
import com.analyseparts.analysis.internal.ReportAnalyst;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FilingRef;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.market.MarketDataService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La file de lecture du lecteur local (ADR 0019) : ce qui est relu, et l'écriture par paquets. */
class AnalysisServiceTest {

    final CompanyRepository companies = mock(CompanyRepository.class);
    final FinancialsService financials = mock(FinancialsService.class);
    final AnalysisRepository repository = mock(AnalysisRepository.class);
    final ReportAnalysisRunner runner = mock(ReportAnalysisRunner.class);

    static Company company(long id) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("SOCIETE " + id);
        return c;
    }

    static FilingRef filing(long id) {
        return new FilingRef("rapport-" + id, LocalDate.of(2025, 12, 31), "https://exemple/" + id);
    }

    static ReportAnalysis analysis(String sourceId) {
        return new ReportAnalysis(sourceId, LocalDate.of(2025, 12, 31), "test", Instant.EPOCH, 1, 1, 0, List.of(),
                Instant.EPOCH);
    }

    AnalysisService service(int saveBatch, long... ids) throws Exception {
        List<Company> all = java.util.Arrays.stream(ids).mapToObj(AnalysisServiceTest::company).toList();
        when(companies.findAll()).thenReturn(all);
        Map<Long, FilingRef> latest = new java.util.HashMap<>();
        for (long id : ids) {
            latest.put(id, filing(id));
            when(financials.findLatestFiling(id)).thenReturn(Optional.of(filing(id)));
        }
        when(financials.findLatestFilings()).thenReturn(latest);
        when(runner.run(anyString(), any())).thenAnswer(call -> analysis(call.<FilingRef>getArgument(1).sourceId()));
        return new AnalysisService(companies, financials, repository, runner, mock(ReportAnalyst.class),
                mock(MarketDataService.class), saveBatch);
    }

    static void awaitIdle(AnalysisService service) throws InterruptedException {
        for (int i = 0; i < 200 && !service.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertThat(service.isIdle()).isTrue();
    }

    @Test
    void onlyReportsNotYetReadAreQueued() throws Exception {
        when(repository.findAnalysedSourceIds()).thenReturn(Set.of("rapport-2"));
        AnalysisService service = service(1, 1, 2, 3);

        assertThat(service.requestMissing()).isEqualTo(2);
        awaitIdle(service);
        verify(repository).save(eq(1L), any());
        verify(repository, never()).save(eq(2L), any());
        verify(repository).save(eq(3L), any());
    }

    @Test
    void readingsAreSavedInBatchesAndTheLastOneWhenTheQueueEmpties() throws Exception {
        when(repository.findAnalysedSourceIds()).thenReturn(Set.of());
        AnalysisService service = service(2, 1, 2, 3);

        assertThat(service.requestMissing()).isEqualTo(3);
        awaitIdle(service);
        Thread.sleep(50); // la dernière écriture suit la fin de la lecture
        verify(repository, times(3)).save(anyLong(), any());
    }

    @Test
    void aReportThatFailedIsNotRetriedOnTheNextRound() throws Exception {
        when(repository.findAnalysedSourceIds()).thenReturn(Set.of());
        AnalysisService service = service(1, 1);
        org.mockito.Mockito.doThrow(new java.io.IOException("rapport introuvable")).when(runner).run(anyString(), any());

        assertThat(service.requestMissing()).isEqualTo(1);
        awaitIdle(service);
        assertThat(service.requestMissing()).isZero();
    }
}
