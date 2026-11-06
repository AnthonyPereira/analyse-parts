package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FilingData;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.financials.ReportedFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.FilingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Importe les rapports annuels ESEF des sociétés qui ont un LEI.
 * Le rythme des requêtes est géré par {@link FilingsXbrlClient}.
 */
@Service
public class EsefImportService {

    private static final Logger log = LoggerFactory.getLogger(EsefImportService.class);

    private final FilingsXbrlClient client;
    private final FinancialsService financials;
    private final CompanyRepository companies;
    private final int filingsPerCompany;

    public EsefImportService(FilingsXbrlClient client, FinancialsService financials, CompanyRepository companies,
                             @Value("${analyse-parts.esef.filings-per-company:3}") int filingsPerCompany) {
        this.client = client;
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

    ImportResult importCompany(Company company) {
        if (company.getLei() == null) {
            return new ImportResult(1, 0, 0, 0, 0);
        }
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
                    financials.recordFiling(company.getId(), new FilingData(filing.fxoId(),
                            LocalDate.parse(filing.periodEnd()), filing.country(), filing.jsonUrl(),
                            filing.errorCount()), facts);
                    imported++;
                    factCount += facts.size();
                    log.info("ESEF {} {} : {} faits", company.getName(), filing.periodEnd(), facts.size());
                } catch (RestClientException | DataAccessException e) {
                    // Un rapport en échec (téléchargement ou enregistrement) n'arrête pas les autres
                    log.warn("ESEF {} : échec pour {} : {}", company.getName(), filing.jsonUrl(), e.getMessage());
                    errors++;
                }
            }
            if (errors == 0) {
                // Tout s'est bien passé (y compris "aucun rapport") : on ne la redemandera pas
                company.markEsefChecked(Instant.now());
                companies.save(company);
            }
        } catch (RestClientException e) {
            log.warn("ESEF {} : index indisponible : {}", company.getName(), e.getMessage());
            errors++;
        }
        return new ImportResult(1, imported, skipped, factCount, errors);
    }
}
