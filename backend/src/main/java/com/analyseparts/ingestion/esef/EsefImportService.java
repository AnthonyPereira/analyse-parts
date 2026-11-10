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
import org.springframework.jdbc.core.JdbcTemplate;
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

    /**
     * Un exercice dure un an ; un rapport dont les chiffres de flux couvrent moins de 300 jours est
     * un rapport trimestriel ou semestriel (déposé en ESEF par certaines sociétés nordiques, comme
     * Maersk) : il ne donne pas un exercice comparable aux autres (ADR 0020).
     */
    static final int MIN_ANNUAL_DAYS = 300;

    /** Rapports téléchargés au plus par société : 3 fois le nombre d'exercices voulus (intermédiaires compris). */
    private static final int MAX_DOWNLOADS_FACTOR = 3;

    private final FilingsXbrlClient client;
    private final FinancialsService financials;
    private final CompanyRepository companies;
    private final int filingsPerCompany;
    /** Source complémentaire (null dans les tests qui ne la simulent pas). */
    private final AmfFilingsClient amf;
    private final SourceHealth health;
    private final JdbcTemplate jdbc;

    @Autowired
    public EsefImportService(FilingsXbrlClient client, AmfFilingsClient amf, FinancialsService financials,
                             CompanyRepository companies,
                             @Value("${analyse-parts.esef.filings-per-company:3}") int filingsPerCompany,
                             @Value("${analyse-parts.amf.enabled:true}") boolean amfEnabled,
                             SourceHealth health, JdbcTemplate jdbc) {
        this.health = health;
        this.jdbc = jdbc;
        this.client = client;
        this.amf = amfEnabled ? amf : null;
        this.financials = financials;
        this.companies = companies;
        this.filingsPerCompany = filingsPerCompany;
    }

    public record ImportResult(int companies, int filingsImported, int filingsSkipped, int factCount, int errors) {

        static final ImportResult EMPTY = new ImportResult(0, 0, 0, 0, 0);

        ImportResult add(ImportResult o) {
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
    static boolean hasNoIfrsFacts(List<ReportedFact> facts) {
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
            total = total.add(importCompany(company, true, checks));
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
        Map<Long, LocalDate> latest = financials.findLatestPeriodEnds();
        List<Company> due = companies.findByLeiIsNotNullOrderByName().stream()
                .filter(c -> EsefRefreshPolicy.isDue(c.getEsefCheckedAt(), latest.get(c.getId()), now, ignoreRecentCheck))
                .limit(limit)
                .toList();
        log.info("Rafraîchissement ESEF : {} sociétés à vérifier", due.size());
        ImportResult total = ImportResult.EMPTY;
        Checks checks = new Checks();
        for (Company company : due) {
            total = total.add(importCompany(company, useFilingsXbrlOrg, checks));
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
        Map<Long, LocalDate> latest = financials.findLatestPeriodEnds();
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
            total = total.add(importFromAmf(company, checks.amf()));
        }
        report(checks);
        log.info("Vérification AMF du jour : {}", total);
        return total;
    }

    /**
     * Un paquet ESEF donné par son adresse, pour une société que ni l'AMF ni filings.xbrl.org ne
     * couvrent à jour : Aperam (autorité luxembourgeoise) n'a que son rapport 2021 sur
     * filings.xbrl.org, mais publie les suivants sur son propre site. Le paquet doit être balisé
     * avec le LEI de la société ; un exercice déjà connu n'est pas réimporté.
     */
    public ImportResult importPackage(String isin, String url) {
        Company company = companies.findByIsin(isin)
                .orElseThrow(() -> new IllegalArgumentException("ISIN inconnu : " + isin));
        if (amf == null) {
            throw new IllegalStateException("Téléchargement des paquets désactivé (analyse-parts.amf.enabled=false)");
        }
        if (financials.isImported(url)) {
            return new ImportResult(1, 0, 1, 0, 0);
        }
        InlineXbrlParser.ParsedReport parsed;
        try {
            parsed = InlineXbrlParser.parsePackage(new ByteArrayInputStream(amf.download(url)));
        } catch (IOException | RestClientException e) {
            log.warn("Paquet {} : échec pour {} : {}", company.getName(), url, e.getMessage());
            return new ImportResult(1, 0, 0, 0, 1);
        }
        if (company.getLei() == null || !company.getLei().equals(parsed.lei())) {
            throw new IllegalArgumentException("Le paquet est balisé avec le LEI " + parsed.lei()
                    + ", pas celui de " + company.getName() + " (" + company.getLei() + ")");
        }
        if (hasNoIfrsFacts(parsed.facts())) {
            throw new IllegalArgumentException("Aucun chiffre IFRS reconnu dans " + url);
        }
        LocalDate periodEnd = parsed.facts().stream().filter(f -> !f.isInstant())
                .map(ReportedFact::periodEnd).max(LocalDate::compareTo).orElseThrow();
        if (isKnown(new HashSet<>(financials.findPeriodEnds(company.getId())), periodEnd)) {
            return new ImportResult(1, 0, 1, 0, 0);
        }
        // l'adresse sert d'identifiant ; commençant par http, le rapport est relu depuis le paquet
        financials.recordFiling(company.getId(), new FilingData(url, periodEnd, company.getCountry(), url, null),
                parsed.facts());
        log.info("Paquet {} {} : {} faits ({})", company.getName(), periodEnd, parsed.facts().size(), url);
        return new ImportResult(1, 1, 0, parsed.facts().size(), 0);
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
        // seules les sociétés cotées à Paris déposent leurs rapports à l'AMF (ADR 0020)
        ImportResult amfResult = amf == null || !"XPAR".equals(company.getMic())
                ? ImportResult.EMPTY : importFromAmf(company, checks.amf());
        Set<LocalDate> known = new HashSet<>(financials.findPeriodEnds(company.getId()));
        ImportResult xbrlOrg = useFilingsXbrlOrg && needsComplement(known, LocalDate.now(), filingsPerCompany)
                ? importFromFilingsXbrlOrg(company, known, checks.filingsXbrl()) : ImportResult.EMPTY;
        ImportResult total = new ImportResult(1, xbrlOrg.filingsImported() + amfResult.filingsImported(),
                xbrlOrg.filingsSkipped() + amfResult.filingsSkipped(), xbrlOrg.factCount() + amfResult.factCount(),
                xbrlOrg.errors() + amfResult.errors());
        if (total.errors() == 0) {
            // Tout s'est bien passé (y compris "aucun rapport") : pas de nouvel essai avant la date prévue
            // relue avant d'enregistrer : les téléchargements ont pris du temps, et une autre collecte
            // (fiches, LEI) a pu modifier la société entre-temps
            companies.findById(company.getId()).ifPresent(fresh -> {
                fresh.markEsefChecked(Instant.now());
                companies.save(fresh);
            });
        }
        return total;
    }

    /**
     * Rapports ESEF déposés à l'AMF, pour les exercices que l'on n'a pas encore. On ne regarde
     * que les dépôts publiés après la clôture du dernier exercice connu, et on s'arrête à
     * {@code filingsPerCompany} nouveaux exercices.
     */
    private ImportResult importFromAmf(Company company, Tally tally) {
        Set<LocalDate> known = new HashSet<>(financials.findPeriodEnds(company.getId()));
        LocalDate since = known.stream().max(LocalDate::compareTo).map(d -> d.plusMonths(1))
                .orElse(LocalDate.now().minusYears(4));
        int imported = 0, skipped = 0, factCount = 0, errors = 0;
        List<AmfFilingsClient.AmfFiling> reports;
        try {
            reports = amf.findAnnualReports(company.getIsin(), company.getLei(), since);
            tally.recordSuccess();
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
                if (hasNoIfrsFacts(parsed.facts())) {
                    log.warn("AMF {} : aucun chiffre IFRS reconnu dans {}", company.getName(), report.fileName());
                    tally.fail("rapport " + report.fileName() + " lu, mais aucun chiffre IFRS reconnu : "
                            + "format du paquet ESEF changé ?");
                    skipped++; // comptes non consolidés possibles : pas une erreur d'import, mais signalé à SourceHealth
                    continue;
                }
                tally.recordSuccess();
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
                    client.listFilings(company.getLei()), company.getCountry(), Integer.MAX_VALUE);
            tally.recordSuccess();
            Set<String> interim = findInterimFilings(selected);
            int annual = 0, downloads = 0;
            for (FilingInfo filing : selected) {
                if (annual >= filingsPerCompany || downloads >= MAX_DOWNLOADS_FACTOR * filingsPerCompany) {
                    break;
                }
                if (interim.contains(filing.fxoId())) {
                    continue; // rapport trimestriel ou semestriel, déjà vu
                }
                if (financials.isImported(filing.fxoId()) || isKnown(known, LocalDate.parse(filing.periodEnd()))) {
                    annual++;
                    skipped++; // déjà importé, ou exercice déjà fourni par l'AMF
                    continue;
                }
                downloads++;
                try {
                    List<ReportedFact> facts = XbrlJsonParser.extractTotals(client.download(filing.jsonUrl()));
                    if (hasNoIfrsFacts(facts)) {
                        log.warn("ESEF {} : aucun chiffre IFRS reconnu dans {}", company.getName(), filing.jsonUrl());
                        tally.fail("rapport " + filing.fxoId() + " lu, mais aucun chiffre IFRS reconnu : "
                                + "format xBRL-JSON changé ?");
                        skipped++; // comptes non consolidés possibles : pas une erreur d'import, mais signalé à SourceHealth
                        continue;
                    }
                    tally.recordSuccess();
                    LocalDate periodEnd = resolvePeriodEnd(company, filing, facts);
                    if (isInterim(facts, periodEnd)) {
                        markInterim(filing, periodEnd);
                        log.info("ESEF {} {} : rapport intermédiaire, ignoré", company.getName(), periodEnd);
                        skipped++;
                        continue;
                    }
                    annual++;
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

    /** Vrai si le plus long des flux qui se terminent à la clôture dure moins de {@value MIN_ANNUAL_DAYS} jours. */
    static boolean isInterim(List<ReportedFact> facts, LocalDate periodEnd) {
        return facts.stream()
                .filter(f -> !f.isInstant())
                .filter(f -> Math.abs(ChronoUnit.DAYS.between(f.periodEnd(), periodEnd)) <= MAX_PERIOD_END_GAP_DAYS)
                .mapToLong(f -> ChronoUnit.DAYS.between(f.periodStart(), f.periodEnd()))
                .max()
                .stream().anyMatch(days -> days < MIN_ANNUAL_DAYS);
    }

    private Set<String> findInterimFilings(List<FilingInfo> filings) {
        if (filings.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT source_id FROM esef_interim_filing WHERE source_id = ANY(?)",
                String.class, (Object) filings.stream().map(FilingInfo::fxoId).toArray(String[]::new)));
    }

    private void markInterim(FilingInfo filing, LocalDate periodEnd) {
        jdbc.update("INSERT INTO esef_interim_filing (source_id, period_end) VALUES (?, ?) ON CONFLICT DO NOTHING",
                filing.fxoId(), periodEnd);
    }

    /**
     * Date de clôture du rapport, déduite de ses faits : la fin la plus tardive des périodes
     * de flux. L'index de la source se trompe parfois (rapports 2025 de Carrefour, Hermès et
     * Rubis déclarés au 31/12/2026) : les faits font foi.
     */
    static LocalDate resolvePeriodEnd(Company company, FilingInfo filing, List<ReportedFact> facts) {
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
