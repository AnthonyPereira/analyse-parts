package com.analyseparts.analysis;

import com.analyseparts.analysis.AnalysisView.State;
import com.analyseparts.analysis.internal.HalfYearChecks;
import com.analyseparts.analysis.internal.HalfYearReader;
import com.analyseparts.analysis.internal.HalfYearRepository;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.ingestion.HalfYearDocuments;
import com.analyseparts.ingestion.HalfYearDocuments.HalfYearDocument;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Les rapports financiers semestriels : les trouver à l'AMF, en lire le compte de résultat, et en
 * tirer les chiffres sur 12 mois glissants (ADR 0015).
 * <p>
 * Comme les lectures de rapports annuels, une lecture occupe la carte graphique : elles passent une
 * par une. Une lecture dure une quinzaine de secondes (une seule question au modèle).
 */
@Service
public class HalfYearService {

    private static final Logger log = LoggerFactory.getLogger(HalfYearService.class);

    /** Dépôts essayés par semestre : le rapport, sa version anglaise, une version corrigée… */
    static final int MAX_DOCUMENTS = 3;

    private final CompanyRepository companies;
    private final FinancialsService financials;
    private final HalfYearDocuments documents;
    private final HalfYearReader reader;
    private final HalfYearRepository repository;
    private final String model;
    private final Clock clock = Clock.systemDefaultZone();

    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> new Thread(r, "half-year"));
    private final Map<Long, State> pending = new ConcurrentHashMap<>();
    private final Map<Long, String> errors = new ConcurrentHashMap<>();

    public HalfYearService(CompanyRepository companies, FinancialsService financials, HalfYearDocuments documents,
                           HalfYearReader reader, HalfYearRepository repository,
                           @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.companies = companies;
        this.financials = financials;
        this.documents = documents;
        this.reader = reader;
        this.repository = repository;
        this.model = model;
    }

    /** Le dernier semestre lu et les chiffres sur 12 mois ; vide si la société est inconnue. */
    public Optional<HalfYearView> view(String isin) {
        return companies.findByIsin(isin).map(c -> view(c.getId()));
    }

    private HalfYearView view(long companyId) {
        Optional<HalfYearReport> report = repository.latest(companyId);
        State state = pending.getOrDefault(companyId,
                errors.containsKey(companyId) ? State.FAILED : report.isPresent() ? State.DONE : State.NONE);
        List<HalfYearView.Ttm> ttm = report.flatMap(r -> latestYear(companyId)
                        .filter(y -> HalfYearChecks.halfYearEnd(y.fiscalYearEnd()).equals(r.periodEnd()))
                        .map(y -> HalfYearChecks.ttm(r, y)))
                .orElse(List.of());
        return new HalfYearView(state, errors.get(companyId), report.orElse(null), ttm);
    }

    /** Lit le rapport semestriel qui suit le dernier exercice d'une société (même s'il a déjà été lu). */
    public Optional<HalfYearView> request(String isin) {
        return companies.findByIsin(isin).map(c -> {
            enqueue(c);
            return view(c.getId());
        });
    }

    /** Met dans la file chaque société dont le semestre attendu est clos et pas encore lu. */
    public int requestMissing() {
        int queued = 0;
        LocalDate today = LocalDate.now(clock);
        for (Company company : companies.findAll()) {
            Optional<FinancialHistory.Year> year = latestYear(company.getId());
            if (year.isEmpty()) {
                continue;
            }
            LocalDate expected = HalfYearChecks.halfYearEnd(year.get().fiscalYearEnd());
            boolean done = repository.latest(company.getId()).filter(r -> !r.periodEnd().isBefore(expected)).isPresent();
            if (!expected.isAfter(today) && !done && enqueue(company)) {
                queued++;
            }
        }
        log.info("{} rapports semestriels mis dans la file", queued);
        return queued;
    }

    private Optional<FinancialHistory.Year> latestYear(long companyId) {
        return financials.history(companyId).years().stream()
                .max(Comparator.comparing(FinancialHistory.Year::fiscalYearEnd));
    }

    private boolean enqueue(Company company) {
        if (pending.putIfAbsent(company.getId(), State.QUEUED) != null) {
            return false;
        }
        queue.submit(() -> run(company));
        return true;
    }

    private void run(Company company) {
        long id = company.getId();
        pending.put(id, State.RUNNING);
        try {
            read(company);
            errors.remove(id);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            errors.put(id, message);
            log.warn("Semestriel {} : {}", company.getName(), message);
        } finally {
            pending.remove(id);
        }
    }

    private void read(Company company) throws Exception {
        FinancialHistory.Year year = latestYear(company.getId())
                .orElseThrow(() -> new IllegalStateException("Aucun exercice annuel enregistré"));
        LocalDate expected = HalfYearChecks.halfYearEnd(year.fiscalYearEnd());
        if (expected.isAfter(LocalDate.now(clock))) {
            throw new IllegalStateException("Le semestre qui suit l'exercice " + year.fiscalYearEnd()
                    + " n'est pas encore clos");
        }
        List<HalfYearDocument> found = documents.reports(company.getIsin(), company.getLei(), expected);
        if (found.isEmpty()) {
            throw new IllegalStateException("Aucun rapport semestriel déposé à l'AMF depuis le " + expected);
        }
        List<String> reasons = new ArrayList<>();
        for (HalfYearDocument document : found.subList(0, Math.min(MAX_DOCUMENTS, found.size()))) {
            Optional<HalfYearReader.Reading> reading = reader.read(company.getName(), documents.download(document.url()),
                    expected, year.fiscalYearEnd());
            if (reading.isEmpty()) {
                reasons.add(document.title() + " : pas de compte de résultat trouvé");
                continue;
            }
            HalfYearChecks.Checked checked = HalfYearChecks.check(reading.get(), year);
            checked.rejected().forEach(r -> log.info("Semestriel {} : écarté, {}", company.getName(), r));
            if (!checked.lines().isEmpty()) {
                repository.save(company.getId(), new HalfYearReport(expected, document.publishedOn(), document.title(),
                        document.url(), model, clock.instant(), checked.lines()));
                log.info("Semestriel {} au {} : {} lignes gardées ({})", company.getName(), expected,
                        checked.lines().size(), document.title());
                return;
            }
            reasons.add(document.title() + " : " + String.join(" ; ", checked.rejected()));
        }
        throw new IllegalStateException("Aucun rapport lisible : " + String.join(" | ", reasons));
    }

    @PreDestroy
    void stop() {
        queue.shutdownNow();
    }
}
