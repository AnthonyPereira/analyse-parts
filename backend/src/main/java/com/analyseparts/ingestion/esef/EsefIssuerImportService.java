package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.CompaniesImported;
import com.analyseparts.ingestion.StockExchange;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.Issuer;
import com.analyseparts.ingestion.gleif.GleifClient;
import com.analyseparts.ingestion.prices.YahooQuoteClient;
import com.analyseparts.ingestion.prices.YahooQuoteClient.YahooListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Les sociétés cotées hors Euronext, trouvées par leur rapport ESEF (ADR 0020).
 * <p>
 * Euronext publie la liste de ses sociétés ; les autres places, pas sous une forme gratuite et
 * stable. On part donc de l'index des rapports ESEF (filings.xbrl.org), qui donne le LEI de chaque
 * émetteur, puis :
 * <ol>
 *   <li>GLEIF donne les codes ISIN du LEI (actions, mais aussi obligations) ;</li>
 *   <li>la recherche de Yahoo dit lequel est une action, sur quelle place, et dans quel secteur ;</li>
 *   <li>si la place est suivie ({@link StockExchange}), la société est créée, avec son LEI et son
 *       secteur ICB déduit de celui de Yahoo ({@link YahooSectorMapping}).</li>
 * </ol>
 * Ses rapports sont ensuite importés comme ceux des autres sociétés (import ESEF des sociétés qui ont
 * un LEI et pas encore de rapport). Chaque émetteur examiné est noté ({@code esef_issuer_check}) :
 * un émetteur sans action n'est pas recherché de nouveau chaque semaine.
 */
@Service
public class EsefIssuerImportService {

    private static final Logger log = LoggerFactory.getLogger(EsefIssuerImportService.class);

    /**
     * Pays non examinés : ceux d'Euronext (leurs sociétés viennent des listes d'Euronext, et les
     * autres y sont sur les marchés Growth, non suivis) et l'Ukraine (plus de 1 000 émetteurs, presque
     * tous non cotés, sur une place non suivie).
     */
    static final Set<String> SKIPPED_COUNTRIES = Set.of("FR", "NL", "BE", "PT", "IE", "NO", "IT", "UA");

    /** Un émetteur sans rapport depuis deux ans n'est sans doute plus coté. */
    static final int MAX_REPORT_AGE_YEARS = 2;

    /** Au plus 6 codes ISIN essayés par émetteur : l'action est presque toujours parmi les premiers. */
    static final int MAX_ISINS = 6;

    public record IssuerImportResult(int issuers, int checked, int created, int notListed, int errors) {
    }

    private final FilingsXbrlClient index;
    private final GleifClient gleif;
    private final YahooQuoteClient yahoo;
    private final YahooSectorMapping sectors;
    private final NasdaqNordicClient nasdaqNordic;
    private final CompanyRepository companies;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Duration pause;
    private final Clock clock = Clock.systemUTC();

    public EsefIssuerImportService(FilingsXbrlClient index, GleifClient gleif, YahooQuoteClient yahoo,
                                   YahooSectorMapping sectors, NasdaqNordicClient nasdaqNordic,
                                   CompanyRepository companies, JdbcTemplate jdbc,
                                   ApplicationEventPublisher events,
                                   @Value("${analyse-parts.esef.issuer-pause:1s}") Duration pause) {
        this.index = index;
        this.gleif = gleif;
        this.yahoo = yahoo;
        this.sectors = sectors;
        this.nasdaqNordic = nasdaqNordic;
        this.companies = companies;
        this.jdbc = jdbc;
        this.events = events;
        this.pause = pause;
    }

    /** Chaque semaine : les nouveaux émetteurs de l'index (une centaine de requêtes, sauf au premier passage). */
    @Scheduled(cron = "${analyse-parts.esef.issuers-cron:0 30 4 * * MON}", zone = "Europe/Paris")
    void importOnSchedule() {
        importIssuers(Integer.MAX_VALUE);
    }

    /** Examine au plus {@code limit} émetteurs pas encore examinés (environ 3 s chacun). */
    public IssuerImportResult importIssuers(int limit) {
        List<Issuer> issuers = selectIssuers(index.listIssuers(), LocalDate.now(clock));
        Set<String> known = new HashSet<>(jdbc.queryForList(
                "SELECT lei FROM esef_issuer_check WHERE outcome <> 'ERROR'", String.class));
        companies.findAll().forEach(c -> {
            if (c.getLei() != null) {
                known.add(c.getLei());
            }
        });
        int checked = 0, created = 0, notListed = 0, errors = 0;
        for (Issuer issuer : issuers) {
            if (checked >= limit) {
                break;
            }
            if (known.contains(issuer.lei())) {
                continue;
            }
            checked++;
            try {
                Optional<Company> company = findListedCompany(issuer);
                record(issuer, company.isPresent() ? "LISTED" : "NOT_LISTED",
                        company.map(Company::getIsin).orElse(null));
                if (company.isPresent()) {
                    created++;
                    log.info("Émetteur ESEF coté : {} ({}, {})", company.get().getName(), company.get().getIsin(),
                            company.get().getMic());
                } else {
                    notListed++;
                }
            } catch (RestClientException e) {
                errors++;
                record(issuer, "ERROR", null);
                log.warn("Émetteur ESEF {} ({}) : {}", issuer.name(), issuer.lei(), e.getMessage());
            }
        }
        if (created > 0) {
            events.publishEvent(new CompaniesImported("ESEF", created, 0));
        }
        var result = new IssuerImportResult(issuers.size(), checked, created, notListed, errors);
        log.info("Émetteurs ESEF hors Euronext : {}", result);
        return result;
    }

    public record SectorUpdateResult(int companies, int changed, int notFound, int errors) {
    }

    /**
     * Redemande à Yahoo le secteur de chaque société suivie hors Euronext et lui réapplique la table
     * {@link YahooSectorMapping}, après une correction de celle-ci (environ 1 s par société). Le
     * sous-secteur Yahoo n'est pas gardé en base : il faut le redemander. Ne touche pas aux sociétés
     * d'Euronext, dont le secteur vient de leur fiche Euronext. Recalibrer ensuite.
     */
    public SectorUpdateResult updateSectors() {
        int count = 0, changed = 0, notFound = 0, errors = 0;
        for (Company company : companies.findAll()) {
            if (StockExchange.findByMic(company.getMic()).filter(m -> !m.isEuronext()).isEmpty()) {
                continue;
            }
            count++;
            try {
                Optional<YahooListing> stock = findStock(yahoo.searchByIsin(company.getIsin()));
                sleep();
                var icb = stock.flatMap(s -> sectors.findIcb(s.sector(), s.industry()));
                if (icb.isEmpty()) {
                    notFound++;
                    continue;
                }
                if (!icb.equals(company.getIcb())) {
                    // relue juste avant l'écriture : une collecte en parallèle a pu la modifier
                    Company fresh = companies.findById(company.getId()).orElse(company);
                    log.info("Secteur de {} : {} → {}", fresh.getName(),
                            fresh.getIcb().map(i -> i.sector()).orElse("aucun"), icb.get().sector());
                    fresh.applySector(icb.get());
                    companies.save(fresh);
                    changed++;
                }
            } catch (RestClientException e) {
                errors++;
                log.warn("Secteur Yahoo de {} : {}", company.getIsin(), e.getMessage());
            }
        }
        var result = new SectorUpdateResult(count, changed, notFound, errors);
        log.info("Secteurs Yahoo réappliqués : {}", result);
        return result;
    }

    /** Les émetteurs à examiner : rapport récent, pays hors Euronext, les plus récents d'abord. */
    static List<Issuer> selectIssuers(List<Issuer> all, LocalDate today) {
        String oldest = today.minusYears(MAX_REPORT_AGE_YEARS).toString();
        return all.stream()
                .filter(i -> i.country() != null && !SKIPPED_COUNTRIES.contains(i.country()))
                .filter(i -> i.latestPeriodEnd().compareTo(oldest) >= 0)
                .sorted(Comparator.comparing(Issuer::latestPeriodEnd).reversed())
                .toList();
    }

    /**
     * L'ordre d'essai des codes ISIN : ceux du pays de l'émetteur d'abord (l'action d'une société
     * suédoise commence par SE). Les obligations internationales (XS) et les titres américains (US,
     * souvent des certificats ADR) sont écartés.
     */
    static List<String> orderIsins(List<String> isins, String country) {
        List<String> ordered = new ArrayList<>();
        isins.stream().filter(i -> i.startsWith(country)).forEach(ordered::add);
        isins.stream().filter(i -> !i.startsWith(country) && !i.startsWith("XS") && !i.startsWith("US"))
                .forEach(ordered::add);
        return ordered.stream().distinct().limit(MAX_ISINS).toList();
    }

    /** L'action cotée de l'émetteur sur une place suivie hors Euronext, créée en base ; vide sinon. */
    private Optional<Company> findListedCompany(Issuer issuer) {
        List<String> isins = new java.util.ArrayList<>(orderIsins(gleif.findIsins(issuer.lei()), issuer.country()));
        sleep();
        // GLEIF ne connaît pas les ISIN des sociétés finlandaises (et d'une partie des suédoises et
        // danoises) : on les cherche par le nom dans la liste de Nasdaq Nordic
        nasdaqNordic.findIsin(issuer.name(), issuer.country()).filter(i -> !isins.contains(i)).ifPresent(isins::add);
        for (String isin : isins) {
            if (companies.findByIsin(isin).isPresent()) {
                return Optional.empty(); // déjà suivie (par exemple cotée aussi sur Euronext)
            }
            List<YahooListing> listings = yahoo.searchByIsin(isin);
            sleep();
            Optional<YahooListing> stock = findStock(listings);
            if (stock.isPresent()) {
                return Optional.of(create(issuer, isin, stock.get()));
            }
        }
        return Optional.empty();
    }

    /** Une action, sur une place suivie hors Euronext. */
    static Optional<YahooListing> findStock(List<YahooListing> listings) {
        return listings.stream()
                .filter(l -> "EQUITY".equals(l.quoteType()) && l.symbol() != null)
                .filter(l -> StockExchange.findByYahooExchange(l.exchange()).filter(m -> !m.isEuronext()).isPresent())
                .findFirst();
    }

    private Company create(Issuer issuer, String isin, YahooListing stock) {
        StockExchange market = StockExchange.findByYahooExchange(stock.exchange()).orElseThrow();
        String ticker = stock.symbol().endsWith(market.getYahooSuffix())
                ? stock.symbol().substring(0, stock.symbol().length() - market.getYahooSuffix().length())
                : stock.symbol();
        String name = stock.longname() != null ? stock.longname() : issuer.name();
        Company company = new Company(isin, name, ticker, issuer.country(), stock.exchDisp());
        company.updateListing(name, ticker, stock.exchDisp(), market.name());
        // le LEI est connu : la recherche GLEIF par ISIN n'a pas à le retrouver
        company.applyLei(issuer.lei(), issuer.name(), issuer.country(), Instant.now(clock));
        sectors.findIcb(stock.sector(), stock.industry()).ifPresent(company::applySector);
        return companies.save(company);
    }

    private void record(Issuer issuer, String outcome, String isin) {
        jdbc.update("""
                INSERT INTO esef_issuer_check (lei, country, checked_at, outcome, isin) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (lei) DO UPDATE SET checked_at = EXCLUDED.checked_at, outcome = EXCLUDED.outcome,
                                                isin = EXCLUDED.isin
                """, issuer.lei(), issuer.country(), Timestamp.from(Instant.now(clock)), outcome, isin);
    }

    private void sleep() {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
