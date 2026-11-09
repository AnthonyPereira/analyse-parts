package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Company;
import com.analyseparts.ingestion.SourceHealth;
import com.analyseparts.ingestion.SourceHealth.Source;
import com.analyseparts.ingestion.SourceHealth.Tally;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FilingData;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.financials.ReportedFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.FilingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Importe les rapports annuels ESEF des sociétés qui ont un LEI.
 * Le rythme des requêtes est géré par {@link FilingsXbrlClient}.
 */
@Service
public class EsefImportService {

    private static final Logger log = LoggerFactory.getLogger(EsefImportService.class);
    private static final int MAX_PERIOD_END_GAP_DAYS = 7;

    private final FilingsXbrlClient client;
    private final FinancialsService financials;
    private final CompanyRepository companies;
    private final int filingsPerCompany;
    /** Source complémentaire (null dans les tests qui ne la simulent pas). */
    private final AmfFilingsClient amf;
    private final SourceHealth health;

    @Autowired
    public EsefImportService(FilingsXbrlClient client, AmfFilingsClient amf, FinancialsService financials,
                             CompanyRepository companies,
                             @Value("${analyse-parts.esef.filings-per-company:3}") int filingsPerCompany,
                             @Value("${analyse-parts.amf.enabled:true}") boolean amfEnabled,
                             SourceHealth health) {
        this.health = health;
        this.client = client;
        this.amf = amfEnabled ? amf : null;
        this.financials = financials;
        this.companies = companies;
        this.filingsPerCompany = filingsPerCompany;
    }

    public record ImportResult(int companies, int filingsImported, int filingsSkipped, int factCount, int errors) {

        static final ImportResult EMPTY = new ImportResult(0, 0, 0, 0, 0);

        ImportResult plus(ImportResult o) {
            return new ImportResult(companies + o.companies, filingsImported + o.filingsImported,
                    filingsSkipped + o.filingsSkipped, factCount + o.factCount, errors + o.errors);
        }
    }

    /**
     * Le bilan d'une collecte, une colonne par source : chaque appel et chaque rapport lu compte
     * pour un essai. Envoyé à {@link SourceHealth} à la fin de la collecte.
     */
    private record Checks(Tally amf, Tally filingsXbrl) {
        Checks() {
            this(new Tally(), new Tally());
        }
    }

    private void report(Checks checks) {
        health.report(Source.AMF, checks.amf());
        health.report(Source.FILINGS_XBRL, checks.filingsXbrl());
    }

    /**
     * Un rapport dont on n'a tiré aucun chiffre IFRS : le fichier a été lu, mais plus rien n'y
     * est reconnu. Le signe d'un changement de format, à signaler plutôt qu'à ignorer.
     */
    static boolean noIfrsFacts(List<ReportedFact> facts) {
        return facts.stream().noneMatch(f -> f.concept().startsWith("ifrs-full:"));
    }

    /** Une société, par ISIN. */
    public ImportResult importCompany(String isin) {
        Company company = companies.findByIsin(isin)
                .orElseThrow(() -> new IllegalArgumentException("ISIN inconnu : " + isin));
        Checks checks = new Checks();
        ImportResult result = importCompany(company, true, checks);
        report(checks);
        return result;
    }

    /** Les sociétés avec un LEI dont l'index ESEF n'a pas encore été consulté avec succès, au plus {@code limit}. */
    public ImportResult importMissing(int limit) {
        ImportResult total = ImportResult.EMPTY;
        Checks checks = new Checks();
        for (Company company : companies.findByLeiIsNotNullAndEsefCheckedAtIsNullOrderByName().stream().limit(limit).toList()) {
            total = total.plus(importCompany(company, true, checks));
        }
        report(checks);
        log.info("Import ESEF : {}", total);
        return total;
    }

    /**
     * Rafraîchit les sociétés pour lesquelles un nouveau rapport est probable
     * ({@link EsefRefreshPolicy}), au plus {@code limit}.
     *
     * @param ignoreRecentCheck relance manuelle : ne tient pas compte de la dernière consultation
     */
    public ImportResult refresh(int limit, boolean ignoreRecentCheck) {
        return refresh(limit, ignoreRecentCheck, true);
    }

    /** @param useFilingsXbrlOrg false : seulement l'AMF (filings.xbrl.org limite fortement le nombre de requêtes) */
    public ImportResult refresh(int limit, boolean ignoreRecentCheck, boolean useFilingsXbrlOrg) {
        Instant now = Instant.now();
        Map<Long, LocalDate> latest = financials.latestPeriodEnds();
        List<Company> due = companies.findByLeiIsNotNullOrderByName().stream()
                .filter(c -> EsefRefreshPolicy.isDue(c.getEsefCheckedAt(), latest.get(c.getId()), now, ignoreRecentCheck))
                .limit(limit)
                .toList();
        log.info("Rafraîchissement ESEF : {} sociétés à vérifier", due.size());
        ImportResult total = ImportResult.EMPTY;
        Checks checks = new Checks();
        for (Company company : due) {
            total = total.plus(importCompany(company, useFilingsXbrlOrg, checks));
        }
        report(checks);
        log.info("Rafraîchissement ESEF : {}", total);
        return total;
    }

    /**
     * Vérification quotidienne, à l'AMF seulement, des sociétés dont un nouveau rapport annuel est
     * attendu. L'AMF publie le rapport le jour du dépôt et ne limite pas les requêtes : inutile
     * d'attendre la vérification hebdomadaire. La date de dernière consultation n'est pas modifiée,
     * pour que la vérification hebdomadaire interroge encore filings.xbrl.org (sociétés étrangères).
     */
    public ImportResult refreshFromAmf() {
        Instant now = Instant.now();
        Map<Long, LocalDate> latest = financials.latestPeriodEnds();
        List<Company> due = companies.findByLeiIsNotNullOrderByName().stream()
                .filter(c -> EsefRefreshPolicy.isReportExpected(latest.get(c.getId()), now))
                .toList();
        if (amf == null || due.isEmpty()) {
            return ImportResult.EMPTY;
        }
        log.info("Vérification AMF du jour : {} sociétés attendent un nouveau rapport", due.size());
        ImportResult total = ImportResult.EMPTY;
        Checks checks = new Checks();
        for (Company company : due) {
            total = total.plus(importFromAmf(company, checks.amf()));
        }
        report(checks);
        log.info("Vérification AMF du jour : {}", total);
        return total;
    }

    /**
     * Deux sources, dans cet ordre (ADR 0005) :
     * <ol>
     *   <li><b>l'AMF</b>, source officielle : le rapport tel que la société l'a déposé, disponible le
     *       jour même. Seulement les émetteurs dont l'AMF est l'autorité (sociétés françaises) ;</li>
     *   <li>filings.xbrl.org, en complément : seulement pour les exercices que l'AMF n'a pas fournis
     *       (sociétés étrangères cotées à Paris, dépôts anciens), et pas du tout si l'on a déjà
     *       les {@code filingsPerCompany} derniers exercices.</li>
     * </ol>
     * Les deux sources publient le même document ESEF : un exercice déjà importé n'est jamais
     * réimporté depuis l'autre.
     */
    private ImportResult importCompany(Company company, boolean useFilingsXbrlOrg, Checks checks) {
        if (company.getLei() == null) {
            return new ImportResult(1, 0, 0, 0, 0);
        }
        ImportResult amfResult = amf == null ? ImportResult.EMPTY : importFromAmf(company, checks.amf());
        Set<LocalDate> known = new HashSet<>(financials.periodEnds(company.getId()));
        ImportResult xbrlOrg = useFilingsXbrlOrg && needsComplement(known, LocalDate.now(), filingsPerCompany)
                ? importFromFilingsXbrlOrg(company, known, checks.filingsXbrl()) : ImportResult.EMPTY;
        ImportResult total = new ImportResult(1, xbrlOrg.filingsImported() + amfResult.filingsImported(),
                xbrlOrg.filingsSkipped() + amfResult.filingsSkipped(), xbrlOrg.factCount() + amfResult.factCount(),
                xbrlOrg.errors() + amfResult.errors());
        if (total.errors() == 0) {
            // Tout s'est bien passé (y compris "aucun rapport") : pas de nouvel essai avant la date prévue
            company.markEsefChecked(Instant.now());
            companies.save(company);
        }
        return total;
    }

    /**
     * Rapports ESEF déposés à l'AMF, pour les exercices que l'on n'a pas encore. On ne regarde
     * que les dépôts publiés après la clôture du dernier exercice connu, et on s'arrête à
     * {@code filingsPerCompany} nouveaux exercices.
     */
    private ImportResult importFromAmf(Company company, Tally tally) {
        Set<LocalDate> known = new HashSet<>(financials.periodEnds(company.getId()));
        LocalDate since = known.stream().max(LocalDate::compareTo).map(d -> d.plusMonths(1))
                .orElse(LocalDate.now().minusYears(4));
        int imported = 0, skipped = 0, factCount = 0, errors = 0;
        List<AmfFilingsClient.AmfFiling> reports;
        try {
            reports = amf.annualReports(company.getIsin(), company.getLei(), since);
            tally.ok();
        } catch (RestClientException e) {
            log.warn("AMF {} : flux indisponible : {}", company.getName(), e.getMessage());
            tally.fail("flux de l'AMF indisponible ou illisible : " + e.getMessage());
            return new ImportResult(1, 0, 0, 0, 1);
        }
        for (AmfFilingsClient.AmfFiling report : reports) {
            String sourceId = "AMF-" + report.fileName();
            if (imported >= filingsPerCompany || financials.isImported(sourceId)) {
                skipped++;
                continue;
            }
            try {
                InlineXbrlParser.ParsedReport parsed =
                        InlineXbrlParser.parsePackage(new ByteArrayInputStream(amf.download(report.url())));
                if (noIfrsFacts(parsed.facts())) {
                    log.warn("AMF {} : aucun chiffre IFRS reconnu dans {}", company.getName(), report.fileName());
                    tally.fail("rapport " + report.fileName() + " lu, mais aucun chiffre IFRS reconnu : "
                            + "format du paquet ESEF changé ?");
                    skipped++; // comptes non consolidés possibles : pas une erreur d'import, mais signalé à SourceHealth
                    continue;
                }
                tally.ok();
                // Un rapport balisé avec un autre LEI est écarté, sauf si l'AMF l'a enregistré sous le LEI
                // de la société : OPmobility balise ses rapports avec l'ancien LEI de Plastic Omnium.
                if (parsed.lei() != null && !parsed.lei().equals(company.getLei())
                        && !(company.getLei() != null && company.getLei().equals(report.lei()))) {
                    log.warn("AMF {} : {} est le rapport d'une autre entité ({})", company.getName(), report.fileName(), parsed.lei());
                    skipped++;
                    continue;
                }
                LocalDate periodEnd = parsed.facts().stream().filter(f -> !f.isInstant())
                        .map(ReportedFact::periodEnd).max(LocalDate::compareTo).orElse(null);
                if (periodEnd == null || isKnown(known, periodEnd)) {
                    skipped++; // exercice déjà connu (import précédent, ou autre version du même rapport)
                    continue;
                }
                financials.recordFiling(company.getId(),
                        new FilingData(sourceId, periodEnd, "FR", report.url(), null), parsed.facts());
                known.add(periodEnd);
                imported++;
                factCount += parsed.facts().size();
                log.info("AMF {} {} : {} faits ({})", company.getName(), periodEnd, parsed.facts().size(), report.title());
            } catch (IOException | RestClientException | DataAccessException e) {
                log.warn("AMF {} : échec pour {} : {}", company.getName(), report.url(), e.getMessage());
                tally.fail("téléchargement ou lecture d'un rapport AMF en échec : " + e.getMessage());
                errors++;
            }
        }
        return new ImportResult(1, imported, skipped, factCount, errors);
    }

    /**
     * Faut-il interroger filings.xbrl.org ? Non si l'on a déjà les {@code wanted} derniers
     * exercices, dont le plus récent clos il y a moins de 16 mois (le rapport suivant
     * n'est pas encore publié).
     */
    static boolean needsComplement(Set<LocalDate> known, LocalDate today, int wanted) {
        LocalDate latest = known.stream().max(LocalDate::compareTo).orElse(null);
        if (latest == null || latest.isBefore(today.minusMonths(16))) {
            return true;
        }
        LocalDate oldestWanted = latest.minusYears(wanted).plusMonths(1);
        return known.stream().filter(d -> d.isAfter(oldestWanted)).count() < wanted;
    }

    /** Un exercice déjà connu, à quelques jours près (façons différentes d'écrire la fin de période). */
    static boolean isKnown(Set<LocalDate> known, LocalDate periodEnd) {
        return known.stream().anyMatch(d -> Math.abs(ChronoUnit.DAYS.between(d, periodEnd)) <= MAX_PERIOD_END_GAP_DAYS);
    }

    private ImportResult importFromFilingsXbrlOrg(Company company, Set<LocalDate> known, Tally tally) {
        int imported = 0, skipped = 0, factCount = 0, errors = 0;
        try {
            List<FilingInfo> selected = FilingSelector.select(
                    client.listFilings(company.getLei()), company.getCountry(), filingsPerCompany);
            tally.ok();
            for (FilingInfo filing : selected) {
                if (financials.isImported(filing.fxoId()) || isKnown(known, LocalDate.parse(filing.periodEnd()))) {
                    skipped++; // déjà importé, ou exercice déjà fourni par l'AMF
                    continue;
                }
                try {
                    List<ReportedFact> facts = XbrlJsonParser.totals(client.download(filing.jsonUrl()));
                    if (noIfrsFacts(facts)) {
                        log.warn("ESEF {} : aucun chiffre IFRS reconnu dans {}", company.getName(), filing.jsonUrl());
                        tally.fail("rapport " + filing.fxoId() + " lu, mais aucun chiffre IFRS reconnu : "
                                + "format xBRL-JSON changé ?");
                        skipped++; // comptes non consolidés possibles : pas une erreur d'import, mais signalé à SourceHealth
                        continue;
                    }
                    tally.ok();
                    LocalDate periodEnd = periodEnd(company, filing, facts);
                    if (isKnown(known, periodEnd)) {
                        skipped++;
                        continue;
                    }
                    known.add(periodEnd);
                    financials.recordFiling(company.getId(), new FilingData(filing.fxoId(),
                            periodEnd, filing.country(), filing.jsonUrl(), filing.errorCount()), facts);
                    imported++;
                    factCount += facts.size();
                    log.info("ESEF {} {} : {} faits", company.getName(), filing.periodEnd(), facts.size());
                } catch (RestClientException | DataAccessException e) {
                    // Un rapport en échec (téléchargement ou enregistrement) n'arrête pas les autres
                    log.warn("ESEF {} : échec pour {} : {}", company.getName(), filing.jsonUrl(), e.getMessage());
                    tally.fail("téléchargement d'un rapport en échec : " + e.getMessage());
                    errors++;
                }
            }
        } catch (RestClientException e) {
            log.warn("ESEF {} : index indisponible : {}", company.getName(), e.getMessage());
            tally.fail("index de filings.xbrl.org indisponible ou illisible : " + e.getMessage());
            errors++;
        }
        return new ImportResult(1, imported, skipped, factCount, errors);
    }

    /**
     * Date de clôture du rapport, déduite de ses faits : la fin la plus tardive des périodes
     * de flux. L'index de la source se trompe parfois (rapports 2025 de Carrefour, Hermès et
     * Rubis déclarés au 31/12/2026) : les faits font foi.
     */
    static LocalDate periodEnd(Company company, FilingInfo filing, List<ReportedFact> facts) {
        LocalDate declared = LocalDate.parse(filing.periodEnd());
        LocalDate fromFacts = facts.stream()
                .filter(f -> !f.isInstant())
                .map(ReportedFact::periodEnd)
                .max(LocalDate::compareTo)
                .orElse(declared);
        // Un écart d'un jour vient d'une façon différente d'écrire la fin de période : on l'ignore.
        if (Math.abs(ChronoUnit.DAYS.between(declared, fromFacts)) <= MAX_PERIOD_END_GAP_DAYS) {
            return declared;
        }
        log.warn("ESEF {} : rapport {} déclaré au {} mais ses chiffres s'arrêtent au {}, on retient le {}",
                company.getName(), filing.fxoId(), declared, fromFacts, fromFacts);
        return fromFacts;
    }
}
