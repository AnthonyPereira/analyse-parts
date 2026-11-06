package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Company;
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

    @Autowired
    public EsefImportService(FilingsXbrlClient client, AmfFilingsClient amf, FinancialsService financials,
                             CompanyRepository companies,
                             @Value("${analyse-parts.esef.filings-per-company:3}") int filingsPerCompany,
                             @Value("${analyse-parts.amf.enabled:true}") boolean amfEnabled) {
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

    /** Une société, par ISIN. */
    public ImportResult importCompany(String isin) {
        return companies.findByIsin(isin).map(this::importCompany)
                .orElseThrow(() -> new IllegalArgumentException("ISIN inconnu : " + isin));
    }

    /** Les sociétés avec un LEI dont l'index ESEF n'a pas encore été consulté avec succès, au plus {@code limit}. */
    public ImportResult importMissing(int limit) {
        ImportResult total = ImportResult.EMPTY;
        for (Company company : companies.findByLeiIsNotNullAndEsefCheckedAtIsNullOrderByName().stream().limit(limit).toList()) {
            total = total.plus(importCompany(company));
        }
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
        for (Company company : due) {
            total = total.plus(importCompany(company, useFilingsXbrlOrg));
        }
        log.info("Rafraîchissement ESEF : {}", total);
        return total;
    }

    /**
     * Deux sources, dans cet ordre :
     * <ol>
     *   <li>filings.xbrl.org : rapports déjà convertis en xBRL-JSON, historique de plusieurs années ;</li>
     *   <li>l'AMF : complète les exercices plus récents que filings.xbrl.org n'a pas encore.</li>
     * </ol>
     */
    ImportResult importCompany(Company company) {
        return importCompany(company, true);
    }

    ImportResult importCompany(Company company, boolean useFilingsXbrlOrg) {
        if (company.getLei() == null) {
            return new ImportResult(1, 0, 0, 0, 0);
        }
        ImportResult xbrlOrg = useFilingsXbrlOrg ? importFromFilingsXbrlOrg(company) : ImportResult.EMPTY;
        ImportResult amfResult = amf == null ? ImportResult.EMPTY : importFromAmf(company);
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
    private ImportResult importFromAmf(Company company) {
        Set<LocalDate> known = new HashSet<>(financials.periodEnds(company.getId()));
        LocalDate since = known.stream().max(LocalDate::compareTo).map(d -> d.plusMonths(1))
                .orElse(LocalDate.now().minusYears(4));
        int imported = 0, skipped = 0, factCount = 0, errors = 0;
        List<AmfFilingsClient.AmfFiling> reports;
        try {
            reports = amf.annualReports(company.getIsin(), company.getLei(), since);
        } catch (RestClientException e) {
            log.warn("AMF {} : flux indisponible : {}", company.getName(), e.getMessage());
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
                if (parsed.lei() != null && !parsed.lei().equals(company.getLei())) {
                    log.warn("AMF {} : {} est le rapport d'une autre entité ({})", company.getName(), report.fileName(), parsed.lei());
                    skipped++;
                    continue;
                }
                LocalDate periodEnd = parsed.facts().stream().filter(f -> !f.isInstant())
                        .map(ReportedFact::periodEnd).max(LocalDate::compareTo).orElse(null);
                if (periodEnd == null || known.contains(periodEnd)) {
                    skipped++; // exercice déjà connu (par filings.xbrl.org, ou autre version du même rapport)
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
                errors++;
            }
        }
        return new ImportResult(1, imported, skipped, factCount, errors);
    }

    private ImportResult importFromFilingsXbrlOrg(Company company) {
        int imported = 0, skipped = 0, factCount = 0, errors = 0;
        try {
            List<FilingInfo> selected = FilingSelector.select(
                    client.listFilings(company.getLei()), company.getCountry(), filingsPerCompany);
            for (FilingInfo filing : selected) {
                if (financials.isImported(filing.fxoId())) {
                    skipped++;
                    continue;
                }
                try {
                    List<ReportedFact> facts = XbrlJsonParser.totals(client.download(filing.jsonUrl()));
                    LocalDate periodEnd = periodEnd(company, filing, facts);
                    financials.recordFiling(company.getId(), new FilingData(filing.fxoId(),
                            periodEnd, filing.country(), filing.jsonUrl(), filing.errorCount()), facts);
                    imported++;
                    factCount += facts.size();
                    log.info("ESEF {} {} : {} faits", company.getName(), filing.periodEnd(), facts.size());
                } catch (RestClientException | DataAccessException e) {
                    // Un rapport en échec (téléchargement ou enregistrement) n'arrête pas les autres
                    log.warn("ESEF {} : échec pour {} : {}", company.getName(), filing.jsonUrl(), e.getMessage());
                    errors++;
                }
            }
        } catch (RestClientException e) {
            log.warn("ESEF {} : index indisponible : {}", company.getName(), e.getMessage());
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
