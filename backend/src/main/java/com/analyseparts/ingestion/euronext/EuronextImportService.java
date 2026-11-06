package com.analyseparts.ingestion.euronext;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.CompaniesImported;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Crée ou met à jour les sociétés à partir de la liste Euronext (clé : ISIN),
 * puis publie {@link CompaniesImported}.
 */
@Service
public class EuronextImportService {

    private static final Logger log = LoggerFactory.getLogger(EuronextImportService.class);

    private final EuronextClient client;
    private final CompanyRepository companies;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public EuronextImportService(EuronextClient client, CompanyRepository companies,
                                 ApplicationEventPublisher events, TransactionTemplate transaction) {
        this.client = client;
        this.companies = companies;
        this.events = events;
        this.transaction = transaction;
    }

    public record ImportResult(int read, int created, int updated) {
    }

    /** Télécharge la liste (hors transaction), puis l'enregistre. */
    public ImportResult importMarket(String mic) {
        return importEquities(mic, client.fetchEquities(mic));
    }

    /**
     * Enregistre les sociétés et publie l'événement <b>dans la même transaction</b> :
     * soit tout est enregistré (sociétés + événement dans le registre Modulith), soit rien.
     * Les listeners ne sont déclenchés qu'après le commit.
     * <p>
     * On utilise un {@link TransactionTemplate} plutôt que {@code @Transactional} : comme
     * {@link #importMarket} appelle cette méthode dans la même classe, l'annotation serait
     * ignorée (le proxy Spring ne voit pas les appels internes).
     */
    public ImportResult importEquities(String mic, List<ListedEquity> equities) {
        return transaction.execute(_ -> {
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
            events.publishEvent(new CompaniesImported(mic, created, updated));
            log.info("Import Euronext {} : {} lues, {} créées, {} mises à jour", mic, equities.size(), created, updated);
            return new ImportResult(equities.size(), created, updated);
        });
    }
}
