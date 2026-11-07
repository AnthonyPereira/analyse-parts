package com.analyseparts.financials;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.internal.FilingEntity;
import com.analyseparts.financials.internal.FilingRepository;
import com.analyseparts.financials.internal.ReportedFactJdbcRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** API publique du module : enregistrer un rapport, lire l'historique d'une société. */
@Service
public class FinancialsService {

    private final FilingRepository filings;
    private final ReportedFactJdbcRepository facts;
    /**
     * Historique de chaque société, gardé en mémoire : le relire en base et le recalculer pour
     * 300 sociétés prenait plus d'une seconde à chaque classement. Il ne change qu'à l'import
     * d'un rapport, qui vide l'entrée de la société.
     */
    private final Map<Long, FinancialHistory> historyCache = new ConcurrentHashMap<>();

    private final CompanyRepository companies;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public FinancialsService(FilingRepository filings, ReportedFactJdbcRepository facts, CompanyRepository companies,
                             ApplicationEventPublisher events) {
        this.filings = filings;
        this.facts = facts;
        this.companies = companies;
        this.events = events;
    }

    public boolean isImported(String sourceId) {
        return filings.existsBySourceId(sourceId);
    }

    public boolean hasFilings(long companyId) {
        return filings.existsByCompanyId(companyId);
    }

    /**
     * Enregistre un rapport et ses faits en une seule transaction, et annonce {@link FilingRecorded}.
     * <p>
     * L'événement est publié <em>dans</em> la transaction : Spring Modulith l'écrit dans la table
     * {@code event_publication} avec le rapport. Si la transaction échoue, il n'existe pas ; si
     * elle réussit, il partira vers Kafka, même si l'application s'arrête juste après.
     */
    @Transactional
    public void recordFiling(long companyId, FilingData filing, List<ReportedFact> reportedFacts) {
        FilingEntity saved = filings.save(new FilingEntity(companyId, filing.sourceId(), filing.periodEnd(),
                filing.country(), filing.jsonUrl(), filing.errorCount(), reportedFacts.size()));
        facts.insertAll(saved.getId(), reportedFacts);
        String isin = companies.findById(companyId).map(Company::getIsin).orElseThrow();
        events.publishEvent(new FilingRecorded(isin, companyId, filing.sourceId(), filing.periodEnd(),
                reportedFacts.size(), Instant.now(clock)));
        historyCache.remove(companyId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // et encore après la validation : une lecture concurrente a pu remettre l'ancien historique
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    historyCache.remove(companyId);
                }
            });
        }
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

    /** Identifiants des sociétés qui ont au moins un rapport (une seule requête). */
    @Transactional(readOnly = true)
    public Set<Long> companiesWithFilings() {
        return latestPeriodEnds().keySet();
    }

    public FinancialHistory history(long companyId) {
        return historyCache.computeIfAbsent(companyId, id -> MetricExtractor.extract(facts.findByCompany(id)));
    }
}
