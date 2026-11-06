package com.analyseparts.financials;

import com.analyseparts.financials.internal.FilingEntity;
import com.analyseparts.financials.internal.FilingRepository;
import com.analyseparts.financials.internal.ReportedFactJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** API publique du module : enregistrer un rapport, lire l'historique d'une société. */
@Service
public class FinancialsService {

    private final FilingRepository filings;
    private final ReportedFactJdbcRepository facts;

    public FinancialsService(FilingRepository filings, ReportedFactJdbcRepository facts) {
        this.filings = filings;
        this.facts = facts;
    }

    public boolean isImported(String sourceId) {
        return filings.existsBySourceId(sourceId);
    }

    public boolean hasFilings(long companyId) {
        return filings.existsByCompanyId(companyId);
    }

    /** Enregistre un rapport et ses faits en une seule transaction. */
    @Transactional
    public void recordFiling(long companyId, FilingData filing, List<ReportedFact> reportedFacts) {
        FilingEntity saved = filings.save(new FilingEntity(companyId, filing.sourceId(), filing.periodEnd(),
                filing.country(), filing.jsonUrl(), filing.errorCount(), reportedFacts.size()));
        facts.insertAll(saved.getId(), reportedFacts);
    }

    /** Dates de clôture des rapports déjà enregistrés pour une société. */
    @Transactional(readOnly = true)
    public List<LocalDate> periodEnds(long companyId) {
        return filings.findPeriodEnds(companyId);
    }

    /** Date de clôture du rapport le plus récent de chaque société qui en a au moins un. */
    @Transactional(readOnly = true)
    public Map<Long, LocalDate> latestPeriodEnds() {
        return filings.findLatestPeriodEnds().stream()
                .collect(Collectors.toMap(FilingRepository.LatestPeriodEnd::getCompanyId,
                        FilingRepository.LatestPeriodEnd::getLatest));
    }

    @Transactional(readOnly = true)
    public FinancialHistory history(long companyId) {
        return MetricExtractor.extract(facts.findByCompany(companyId));
    }
}
