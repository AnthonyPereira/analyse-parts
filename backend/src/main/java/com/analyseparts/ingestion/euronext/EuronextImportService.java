package com.analyseparts.ingestion.euronext;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Crée ou met à jour les sociétés à partir de la liste Euronext (clé : ISIN). */
@Service
public class EuronextImportService {

    private static final Logger log = LoggerFactory.getLogger(EuronextImportService.class);

    private final EuronextClient client;
    private final CompanyRepository companies;

    public EuronextImportService(EuronextClient client, CompanyRepository companies) {
        this.client = client;
        this.companies = companies;
    }

    public record ImportResult(int read, int created, int updated) {
    }

    public ImportResult importMarket(String mic) {
        return importEquities(client.fetchEquities(mic));
    }

    @Transactional
    public ImportResult importEquities(List<ListedEquity> equities) {
        int created = 0;
        int updated = 0;
        for (ListedEquity equity : equities) {
            var existing = companies.findByIsin(equity.isin());
            if (existing.isPresent()) {
                existing.get().updateListing(equity.name(), equity.symbol(), equity.market());
                updated++;
            } else {
                // Pays provisoire = pays de l'ISIN ; GLEIF donnera ensuite le pays du siège.
                companies.save(new Company(equity.isin(), equity.name(), equity.symbol(),
                        equity.isinCountry(), equity.market()));
                created++;
            }
        }
        log.info("Import Euronext : {} lues, {} créées, {} mises à jour", equities.size(), created, updated);
        return new ImportResult(equities.size(), created, updated);
    }
}
