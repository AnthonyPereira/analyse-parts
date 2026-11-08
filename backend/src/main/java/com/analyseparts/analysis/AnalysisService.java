package com.analyseparts.analysis;

import com.analyseparts.analysis.AnalysisView.State;
import com.analyseparts.analysis.internal.AnalysisRepository;
import com.analyseparts.analysis.internal.ReportAnalysisRunner;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FilingRef;
import com.analyseparts.financials.FinancialsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * La file d'attente des lectures de rapports.
 * <p>
 * Une lecture occupe la carte graphique plusieurs minutes : elles passent <b>une par une</b>, dans un
 * seul fil d'exécution. Demander deux fois la même société ne la met pas deux fois dans la file.
 * L'état « en attente / en cours / échec » est gardé en mémoire (perdu au redémarrage, sans gravité) ;
 * les lectures réussies sont en base.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final CompanyRepository companies;
    private final FinancialsService financials;
    private final AnalysisRepository repository;
    private final ReportAnalysisRunner runner;

    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> new Thread(r, "report-analysis"));
    private final Map<Long, State> pending = new ConcurrentHashMap<>();
    private final Map<Long, String> errors = new ConcurrentHashMap<>();

    public AnalysisService(CompanyRepository companies, FinancialsService financials, AnalysisRepository repository,
                           ReportAnalysisRunner runner) {
        this.companies = companies;
        this.financials = financials;
        this.repository = repository;
        this.runner = runner;
    }

    /** Où en est la lecture du rapport d'une société ; vide si la société est inconnue. */
    public Optional<AnalysisView> view(String isin) {
        return companies.findByIsin(isin).map(c -> view(c.getId()));
    }

    private AnalysisView view(long companyId) {
        Optional<ReportAnalysis> last = repository.latest(companyId);
        State state = pending.getOrDefault(companyId,
                errors.containsKey(companyId) ? State.FAILED : last.isPresent() ? State.DONE : State.NONE);
        return new AnalysisView(state, errors.get(companyId), last.orElse(null));
    }

    /** Met la lecture du dernier rapport d'une société dans la file ; vide si la société est inconnue. */
    public Optional<AnalysisView> request(String isin) {
        return companies.findByIsin(isin).map(c -> {
            enqueue(c);
            return view(c.getId());
        });
    }

    /** Met dans la file chaque société dont le dernier rapport n'a pas encore été lu. */
    public int requestMissing() {
        Set<String> done = repository.analysedSourceIds();
        int queued = 0;
        for (Company company : companies.findAll()) {
            Optional<FilingRef> latest = financials.latestFiling(company.getId());
            if (latest.isPresent() && !done.contains(latest.get().sourceId()) && enqueue(company)) {
                queued++;
            }
        }
        log.info("{} rapports mis dans la file de lecture", queued);
        return queued;
    }

    /** Un nouveau rapport : on le lit s'il est le plus récent de la société (pas un exercice passé). */
    public void onNewFiling(long companyId, String sourceId) {
        boolean latest = financials.latestFiling(companyId).map(f -> f.sourceId().equals(sourceId)).orElse(false);
        if (latest) {
            companies.findById(companyId).ifPresent(this::enqueue);
        }
    }

    private boolean enqueue(Company company) {
        long id = company.getId();
        if (pending.putIfAbsent(id, State.QUEUED) != null) {
            return false; // déjà dans la file
        }
        queue.submit(() -> run(company));
        return true;
    }

    private void run(Company company) {
        long id = company.getId();
        pending.put(id, State.RUNNING);
        try {
            FilingRef filing = financials.latestFiling(id)
                    .orElseThrow(() -> new IllegalStateException("Aucun rapport annuel enregistré"));
            ReportAnalysis analysis = runner.run(company.getName(), filing);
            repository.save(id, analysis);
            errors.remove(id);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            errors.put(id, message);
            log.warn("Lecture du rapport de {} en échec : {}", company.getName(), message);
        } finally {
            pending.remove(id);
        }
    }

    @PreDestroy
    void stop() {
        queue.shutdownNow();
    }

}
