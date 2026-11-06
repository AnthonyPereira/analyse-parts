package com.analyseparts.financials;

import com.analyseparts.financials.internal.FilingEntity;
import com.analyseparts.financials.internal.FilingRepository;
import com.analyseparts.financials.internal.ReportedFactJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Transactional(readOnly = true)
    public FinancialHistory history(long companyId) {
        return MetricExtractor.extract(facts.findByCompany(companyId));
    }
}
