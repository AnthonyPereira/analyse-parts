package com.analyseparts.ingestion.prices;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.SourceHealth;
import com.analyseparts.ingestion.SourceHealth.Source;
import com.analyseparts.ingestion.prices.YahooQuoteClient.SparkBatch;
import com.analyseparts.ingestion.prices.YahooQuoteClient.YahooChart;
import com.analyseparts.ingestion.prices.YahooQuoteClient.YahooQuote;
import com.analyseparts.market.MarketDataService;
import com.analyseparts.market.PricePoint;
import com.analyseparts.market.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Met à jour les cours (Yahoo) et les taux de change (BCE).
 * <ul>
 *   <li>cours : <b>chaque minute</b> pendant la séance (9 h – 17 h 45, heure de Paris), soit
 *       ~17 requêtes groupées par minute pour 330 titres. Chaque cours qui a bougé est diffusé
 *       en direct aux navigateurs ouverts ;</li>
 *   <li>historique : clôtures quotidiennes, chaque soir de bourse à 18 h 15 (5 derniers jours,
 *       ou 5 ans pour une société qui n'a encore aucun historique) ;</li>
 *   <li>taux de change : chaque jour ouvré à 16 h 30, après la publication de la BCE.</li>
 * </ul>
 * Après chaque collecte, le bilan part dans {@link SourceHealth} : c'est ainsi qu'on remarque un
 * changement de format chez Yahoo (des réponses, mais plus aucun cours exploitable).
 */
@Service
public class QuoteRefreshService {

    private static final Logger log = LoggerFactory.getLogger(QuoteRefreshService.class);
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    /** Clôture à 17 h 30, fixing jusqu'à 17 h 35, et ~15 min de différé chez Yahoo. */
    private static final LocalTime LAST_UPDATE = LocalTime.of(17, 55);

    /** Pour chaque code MIC : suffixe du symbole Yahoo et code de place renvoyé par Yahoo. */
    record YahooMarket(String suffix, String exchange) {
    }

    private static final Map<String, YahooMarket> YAHOO_MARKETS = Map.of("XPAR", new YahooMarket(".PA", "PAR"));

    private final YahooQuoteClient yahoo;
    private final EcbFxClient ecb;
    private final CompanyRepository companies;
    private final MarketDataService market;
    private final long historyPauseMillis;
    private final SourceHealth health;

    public QuoteRefreshService(YahooQuoteClient yahoo, EcbFxClient ecb, CompanyRepository companies,
                               MarketDataService market,
                               @Value("${analyse-parts.prices.history-pause-ms:300}") long historyPauseMillis,
                               SourceHealth health) {
        this.health = health;
        this.yahoo = yahoo;
        this.ecb = ecb;
        this.companies = companies;
        this.market = market;
        this.historyPauseMillis = historyPauseMillis;
    }

    public record RefreshResult(int requested, int received, int recorded, int errors) {
    }

    public record HistoryResult(int companies, int closes, int errors) {
    }

    @Scheduled(fixedDelayString = "${analyse-parts.prices.live-interval-ms:60000}", initialDelay = 30_000)
    void liveQuotes() {
        if (isMarketHours(ZonedDateTime.now(PARIS))) {
            refreshQuotes();
        }
    }

    @Scheduled(cron = "${analyse-parts.prices.history-cron:0 15 18 * * MON-FRI}", zone = "Europe/Paris")
    void scheduledHistory() {
        refreshHistory(false);
    }

    @Scheduled(cron = "${analyse-parts.fx.cron:0 30 16 * * MON-FRI}", zone = "Europe/Paris")
    void scheduledFx() {
        refreshFxRates();
    }

    static boolean isMarketHours(ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !time.isBefore(OPEN) && time.isBefore(LAST_UPDATE);
    }

    public RefreshResult refreshQuotes() {
        Map<String, Company> bySymbol = yahooCompanies();
        List<String> symbols = List.copyOf(bySymbol.keySet());
        List<Quote> quotes = new ArrayList<>();
        int received = 0, errors = 0, results = 0;
        String problem = null;
        for (int i = 0; i < symbols.size(); i += YahooQuoteClient.MAX_SYMBOLS) {
            List<String> batch = symbols.subList(i, Math.min(i + YahooQuoteClient.MAX_SYMBOLS, symbols.size()));
            try {
                SparkBatch answer = yahoo.spark(batch);
                results += answer.results();
                if (answer.looksLikeFormatChange() && problem == null) {
                    problem = "Yahoo répond, mais aucun cours n'est exploitable (prix ou heure absents) : "
                            + "son format a probablement changé";
                }
                for (YahooQuote q : answer.usable()) {
                    received++;
                    Company company = bySymbol.get(q.symbol());
                    // On vérifie la place de cotation : un même mnémonique peut exister ailleurs.
                    if (company != null && YAHOO_MARKETS.get(company.getMic()).exchange().equals(q.exchange())) {
                        quotes.add(new Quote(company.getId(), q.price(), q.currency(), q.marketTime(), "YAHOO",
                                q.previousClose()));
                    }
                }
            } catch (RestClientException e) {
                log.warn("Yahoo : échec pour {} : {}", batch, e.getMessage());
                errors++;
                if (problem == null) {
                    problem = "Yahoo injoignable ou réponse illisible : " + e.getMessage();
                }
            }
        }
        if (problem == null && results == 0 && !symbols.isEmpty()) {
            problem = "Yahoo ne renvoie aucun résultat";
        }
        health.report(Source.YAHOO_QUOTES, symbols.size(), quotes.size(), problem);
        int recorded = market.recordQuotes(quotes);
        RefreshResult result = new RefreshResult(symbols.size(), received, recorded, errors);
        log.debug("Cours Yahoo : {}", result);
        return result;
    }

    /**
     * Clôtures quotidiennes. Une société sans historique reçoit 5 ans d'un coup, les autres les
     * 5 derniers jours (rattrape un week-end ou une panne).
     *
     * @param full true : recharge 5 ans pour toutes les sociétés
     */
    public HistoryResult refreshHistory(boolean full) {
        Map<Long, LocalDate> latest = market.latestCloseDays();
        int done = 0, closes = 0, errors = 0;
        SourceHealth.Tally tally = new SourceHealth.Tally();
        for (Map.Entry<String, Company> e : yahooCompanies().entrySet()) {
            Company company = e.getValue();
            String range = full || !latest.containsKey(company.getId()) ? "5y" : "5d";
            try {
                Optional<YahooChart> chart = yahoo.chart(e.getKey(), range, "1d");
                if (chart.isPresent() && YAHOO_MARKETS.get(company.getMic()).exchange().equals(chart.get().exchange())) {
                    List<PricePoint.Close> points = chart.get().points().stream()
                            .map(p -> new PricePoint.Close(LocalDate.ofInstant(p.time(), PARIS), p.close(), p.volume()))
                            .collect(Collectors.toMap(PricePoint.Close::day, Function.identity(), (a, b) -> b,
                                    LinkedHashMap::new))
                            .values().stream().toList();
                    closes += market.recordCloses(company.getId(), points);
                    done++;
                    if (points.isEmpty()) {
                        tally.fail("Yahoo ne renvoie aucune clôture (" + e.getKey() + ") : format changé ?");
                    } else {
                        tally.ok();
                    }
                } else if (chart.isEmpty()) {
                    tally.fail("Yahoo ne renvoie pas d'historique pour " + e.getKey());
                }
                Thread.sleep(historyPauseMillis); // poli avec Yahoo : ~3 requêtes par seconde au plus
            } catch (RuntimeException ex) {
                // RestClientException (réseau, JSON illisible) ou réponse inattendue : on passe à la suivante
                log.warn("Yahoo : historique indisponible pour {} : {}", e.getKey(), ex.getMessage());
                errors++;
                tally.fail("Yahoo, historique de " + e.getKey() + " : " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        health.report(Source.YAHOO_HISTORY, tally);
        HistoryResult result = new HistoryResult(done, closes, errors);
        log.info("Historique des cours : {}", result);
        return result;
    }

    /** La séance du jour d'une société (pour le graphique « 1 jour »). */
    Optional<YahooChart> intraday(Company company) {
        if (company.getTicker() == null || !YAHOO_MARKETS.containsKey(company.getMic())) {
            return Optional.empty();
        }
        return yahoo.chart(yahooSymbol(company), "1d", "5m");
    }

    /** Moins de devises que cela, ou pas de dollar : le fichier de la BCE n'a plus la forme attendue. */
    static final int MIN_FX_RATES = 20;

    public int refreshFxRates() {
        EcbFxClient.FxRates rates;
        try {
            rates = ecb.daily();
        } catch (RuntimeException e) {
            health.down(Source.ECB_FX, "fichier de la BCE indisponible ou illisible : " + e.getMessage());
            throw e;
        }
        if (rates.ratePerEur().size() < MIN_FX_RATES || !rates.ratePerEur().containsKey("USD")) {
            health.down(Source.ECB_FX, "seulement " + rates.ratePerEur().size()
                    + " devises lues dans le fichier de la BCE : son format a probablement changé");
            return 0;
        }
        health.report(Source.ECB_FX, 1, 1, null);
        market.recordFxRates(rates.ratePerEur(), rates.date());
        log.info("Taux BCE du {} : {} devises", rates.date(), rates.ratePerEur().size());
        return rates.ratePerEur().size();
    }

    private Map<String, Company> yahooCompanies() {
        return companies.findAll().stream()
                .filter(c -> c.getTicker() != null && YAHOO_MARKETS.containsKey(c.getMic()))
                .collect(Collectors.toMap(QuoteRefreshService::yahooSymbol, Function.identity(), (a, _) -> a));
    }

    static String yahooSymbol(Company company) {
        return company.getTicker() + YAHOO_MARKETS.get(company.getMic()).suffix();
    }
}
