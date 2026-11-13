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
    public Optional<HalfYearView> findView(String isin) {
        return companies.findByIsin(isin).map(c -> findView(c.getId()));
    }

    private HalfYearView findView(long companyId) {
        Optional<HalfYearReport> report = repository.findLatest(companyId);
        State state = pending.getOrDefault(companyId,
                errors.containsKey(companyId) ? State.FAILED : report.isPresent() ? State.DONE : State.NONE);
        List<HalfYearView.Ttm> ttm = report.flatMap(r -> findLatestYear(companyId)
                        .filter(y -> HalfYearChecks.computeHalfYearEnd(y.fiscalYearEnd()).equals(r.periodEnd()))
                        .map(y -> HalfYearChecks.computeTtm(r, y)))
                .orElse(List.of());
        return new HalfYearView(state, errors.get(companyId), report.orElse(null), ttm);
    }

    /** Lit le rapport semestriel qui suit le dernier exercice d'une société (même s'il a déjà été lu). */
    public Optional<HalfYearView> request(String isin) {
        return companies.findByIsin(isin).map(c -> {
            enqueue(c);
            return findView(c.getId());
        });
    }

    /** Met dans la file chaque société dont le semestre attendu est clos et pas encore lu. */
    public int requestMissing() {
        int queued = 0;
        LocalDate today = LocalDate.now(clock);
        for (Company company : companies.findAll()) {
            Optional<FinancialHistory.Year> year = findLatestYear(company.getId());
            if (year.isEmpty()) {
                continue;
            }
            LocalDate expected = HalfYearChecks.computeHalfYearEnd(year.get().fiscalYearEnd());
            boolean done = repository.findLatest(company.getId()).filter(r -> !r.periodEnd().isBefore(expected)).isPresent();
            if (!expected.isAfter(today) && !done && enqueue(company)) {
                queued++;
            }
        }
        log.info("{} rapports semestriels mis dans la file", queued);
        return queued;
    }

    /** Ce que la revérification a changé. */
    public record Recheck(int checked, List<String> changed, List<String> deleted) {
    }

    /**
     * Revérifie les rapports déjà enregistrés avec les règles actuelles de {@link HalfYearChecks}, sans
     * relire les PDF ni appeler le modèle : retire les lignes écartées, supprime un rapport qui n'a
     * plus de montant confronté aux comptes annuels.
     */
    public Recheck recheckStored() {
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<HalfYearRepository.Stored> stored = repository.findAll();
        for (HalfYearRepository.Stored s : stored) {
            HalfYearReport r = s.report();
            Optional<FinancialHistory.Year> year = financials.getHistory(s.companyId()).years().stream()
                    .filter(y -> HalfYearChecks.computeHalfYearEnd(y.fiscalYearEnd()).equals(r.periodEnd()))
                    .findFirst();
            List<HalfYearReport.Line> kept = HalfYearChecks.recheck(r, year);
            if (kept.size() == r.lines().size()) {
                continue;
            }
            String name = companies.findById(s.companyId()).map(Company::getName).orElse("#" + s.companyId());
            if (kept.isEmpty()) {
                repository.delete(s.companyId(), r.periodEnd());
                deleted.add(name);
            } else {
                repository.updateLines(s.companyId(), r.periodEnd(), kept);
                List<HalfYearReport.Item> removed = r.lines().stream().map(HalfYearReport.Line::item)
                        .filter(i -> kept.stream().noneMatch(k -> k.item() == i)).toList();
                changed.add(name + " " + removed);
            }
        }
        log.info("Semestriels revérifiés : {} ; lignes retirées : {} ; supprimés : {}", stored.size(), changed, deleted);
        return new Recheck(stored.size(), changed, deleted);
    }

    private Optional<FinancialHistory.Year> findLatestYear(long companyId) {
        return financials.getHistory(companyId).years().stream()
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
        FinancialHistory.Year year = findLatestYear(company.getId())
                .orElseThrow(() -> new IllegalStateException("Aucun exercice annuel enregistré"));
        LocalDate expected = HalfYearChecks.computeHalfYearEnd(year.fiscalYearEnd());
        if (expected.isAfter(LocalDate.now(clock))) {
            throw new IllegalStateException("Le semestre qui suit l'exercice " + year.fiscalYearEnd()
                    + " n'est pas encore clos");
        }
        List<HalfYearDocument> found = documents.findReports(company.getIsin(), company.getLei(), expected);
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
