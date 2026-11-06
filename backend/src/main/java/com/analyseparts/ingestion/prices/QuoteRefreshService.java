package com.analyseparts.ingestion.prices;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.ingestion.prices.YahooQuoteClient.YahooQuote;
import com.analyseparts.market.MarketDataService;
import com.analyseparts.market.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Met à jour les cours (Yahoo) et les taux de change (BCE).
 * <ul>
 *   <li>cours : toutes les 15 minutes pendant la séance (9 h – 17 h 45, heure de Paris) ;</li>
 *   <li>taux de change : chaque jour ouvré à 16 h 30, après la publication de la BCE.</li>
 * </ul>
 */
@Service
public class QuoteRefreshService {

    private static final Logger log = LoggerFactory.getLogger(QuoteRefreshService.class);

    /** Pour chaque code MIC : suffixe du symbole Yahoo et code de place renvoyé par Yahoo. */
    record YahooMarket(String suffix, String exchange) {
    }

    private static final Map<String, YahooMarket> YAHOO_MARKETS = Map.of("XPAR", new YahooMarket(".PA", "PAR"));

    private final YahooQuoteClient yahoo;
    private final EcbFxClient ecb;
    private final CompanyRepository companies;
    private final MarketDataService market;

    public QuoteRefreshService(YahooQuoteClient yahoo, EcbFxClient ecb, CompanyRepository companies,
                               MarketDataService market) {
        this.yahoo = yahoo;
        this.ecb = ecb;
        this.companies = companies;
        this.market = market;
    }

    public record RefreshResult(int requested, int received, int recorded, int errors) {
    }

    @Scheduled(cron = "${analyse-parts.prices.cron:0 */15 9-17 * * MON-FRI}", zone = "Europe/Paris")
    void scheduledQuotes() {
        refreshQuotes();
    }

    @Scheduled(cron = "${analyse-parts.fx.cron:0 30 16 * * MON-FRI}", zone = "Europe/Paris")
    void scheduledFx() {
        refreshFxRates();
    }

    public RefreshResult refreshQuotes() {
        Map<String, Company> bySymbol = companies.findAll().stream()
                .filter(c -> c.getTicker() != null && YAHOO_MARKETS.containsKey(c.getMic()))
                .collect(Collectors.toMap(QuoteRefreshService::yahooSymbol, Function.identity(), (a, _) -> a));
        List<String> symbols = List.copyOf(bySymbol.keySet());
        List<Quote> quotes = new ArrayList<>();
        int received = 0, errors = 0;
        for (int i = 0; i < symbols.size(); i += YahooQuoteClient.MAX_SYMBOLS) {
            List<String> batch = symbols.subList(i, Math.min(i + YahooQuoteClient.MAX_SYMBOLS, symbols.size()));
            try {
                for (YahooQuote q : yahoo.quotes(batch)) {
                    received++;
                    Company company = bySymbol.get(q.symbol());
                    // On vérifie la place de cotation : un même mnémonique peut exister ailleurs.
                    if (company != null && YAHOO_MARKETS.get(company.getMic()).exchange().equals(q.exchange())) {
                        quotes.add(new Quote(company.getId(), q.price(), q.currency(), q.marketTime(), "YAHOO"));
                    }
                }
            } catch (RestClientException e) {
                log.warn("Yahoo : échec pour {} : {}", batch, e.getMessage());
                errors++;
            }
        }
        int recorded = market.recordQuotes(quotes);
        RefreshResult result = new RefreshResult(symbols.size(), received, recorded, errors);
        log.info("Cours Yahoo : {}", result);
        return result;
    }

    public int refreshFxRates() {
        EcbFxClient.FxRates rates = ecb.daily();
        market.recordFxRates(rates.ratePerEur(), rates.date());
        log.info("Taux BCE du {} : {} devises", rates.date(), rates.ratePerEur().size());
        return rates.ratePerEur().size();
    }

    static String yahooSymbol(Company company) {
        return company.getTicker() + YAHOO_MARKETS.get(company.getMic()).suffix();
    }
}
