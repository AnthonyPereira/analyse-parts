package com.analyseparts.ingestion.euronext;

import com.analyseparts.ingestion.StockExchange;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.CompaniesImported;
import com.analyseparts.ingestion.SourceHealth;
import com.analyseparts.ingestion.SourceHealth.Source;
import com.analyseparts.market.MarketDataService;
import com.analyseparts.market.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private final MarketDataService market;
    private final SourceHealth health;

    /** Heure de clôture de la séance à Paris (fixing de clôture à 17 h 35). */
    private static final LocalTime CLOSE = LocalTime.of(17, 35);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    public EuronextImportService(EuronextClient client, CompanyRepository companies,
                                 ApplicationEventPublisher events, TransactionTemplate transaction,
                                 MarketDataService market, SourceHealth health) {
        this.market = market;
        this.health = health;
        this.client = client;
        this.companies = companies;
        this.events = events;
        this.transaction = transaction;
    }

    public record ImportResult(int read, int created, int updated) {
    }

    /** Télécharge la liste (hors transaction), puis l'enregistre. */
    /**
     * Euronext Paris liste environ 700 titres, les autres places de 20 (Dublin) à 200 (Milan, Oslo).
     * Bien moins de lignes reconnues : les colonnes du fichier ont probablement changé (le lecteur
     * les repère par leur position).
     */
    static int findMinEquities(String mic) {
        return "XPAR".equals(mic) ? 100 : 10;
    }

    /** Importe les 7 marchés réglementés d'Euronext l'un après l'autre (ADR 0020). */
    public List<ImportResult> importAllMarkets() {
        return StockExchange.findEuronextMarkets().stream().map(m -> importMarket(m.name())).toList();
    }

    public ImportResult importMarket(String mic) {
        List<ListedEquity> equities;
        try {
            equities = client.fetchEquities(mic);
        } catch (RuntimeException e) {
            health.markDown(Source.EURONEXT_LIST, "fichier des actions Euronext indisponible ou illisible : " + e.getMessage());
            throw e;
        }
        if (equities.size() < findMinEquities(mic)) {
            health.markDown(Source.EURONEXT_LIST, "seulement " + equities.size()
                    + " actions reconnues dans le fichier Euronext : ses colonnes ont probablement changé");
        } else {
            health.report(Source.EURONEXT_LIST, 1, 1, null);
        }
        return importEquities(mic, equities);
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
            List<Quote> closingPrices = new ArrayList<>();
            for (ListedEquity equity : equities) {
                // Une action cotée sur plusieurs places figure dans la liste de chacune : on ne la
                // prend que dans celle de son marché principal, qui donne son mnémonique et son cours.
                String primary = findPrimaryMic(equity.market(), mic);
                if (!primary.equals(mic)) {
                    continue;
                }
                var existing = companies.findByIsin(equity.isin());
                Company company;
                if (existing.isPresent()) {
                    company = existing.get();
                    updated++;
                } else {
                    // Pays provisoire = pays de l'ISIN ; GLEIF donnera ensuite le pays du siège.
                    company = companies.save(new Company(equity.isin(), equity.name(), equity.symbol(),
                            equity.extractIsinCountry(), equity.market()));
                    created++;
                }
                company.updateListing(equity.name(), equity.symbol(), equity.market(), mic);
                if (equity.hasClosingPrice()) {
                    closingPrices.add(new Quote(company.getId(), equity.closingPrice(), equity.currency(),
                            equity.closingDate().atTime(CLOSE).atZone(PARIS).toInstant(), "EURONEXT_CLOSE"));
                }
            }
            // Cours de clôture : solution de repli si Yahoo ne répond pas (un cours plus récent n'est pas écrasé)
            companies.flush();
            market.recordQuotes(closingPrices);
            events.publishEvent(new CompaniesImported(mic, created, updated));
            log.info("Import Euronext {} : {} lues, {} créées, {} mises à jour", mic, equities.size(), created, updated);
            return new ImportResult(equities.size(), created, updated);
        });
    }

    /** Le MIC du marché principal (premier de la colonne « Market »), ou celui de la liste lue. */
    static String findPrimaryMic(String markets, String listMic) {
        return StockExchange.findPrimary(markets).map(Enum::name).orElse(listMic);
    }
}
