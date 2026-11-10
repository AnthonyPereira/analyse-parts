package com.analyseparts.market;

import com.analyseparts.company.CompanyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/** Les cours d'une société : dernier cours, historique, courbe du jour. */
@RestController
public class QuoteController {

    private final CompanyRepository companies;
    private final MarketDataService market;

    public QuoteController(CompanyRepository companies, MarketDataService market) {
        this.companies = companies;
        this.market = market;
    }

    public record QuoteView(BigDecimal price, String currency, Instant asOf, String source, BigDecimal previousClose) {
    }

    /** Ex. : GET /api/companies/FR0000121014/quote */
    @GetMapping("/api/companies/{isin}/quote")
    public ResponseEntity<QuoteView> getQuote(@PathVariable String isin) {
        return ResponseEntity.of(companies.findByIsin(isin)
                .flatMap(c -> market.findLatestQuote(c.getId()))
                .map(q -> new QuoteView(q.price(), q.currency(), q.asOf(), q.source(), q.previousClose())));
    }

    /** Durées proposées par le graphique de cours. */
    public enum Range {
        M1(Period.ofMonths(1)), M6(Period.ofMonths(6)), Y1(Period.ofYears(1)), Y5(Period.ofYears(5)), MAX(Period.ofYears(30));

        final Period period;

        Range(Period period) {
            this.period = period;
        }
    }

    /** Clôtures quotidiennes. Ex. : GET /api/companies/FR0000121014/prices?range=Y1 */
    @GetMapping("/api/companies/{isin}/prices")
    public ResponseEntity<List<PricePoint.Close>> getPrices(@PathVariable String isin,
                                                         @RequestParam(defaultValue = "Y1") Range range) {
        return ResponseEntity.of(companies.findByIsin(isin)
                .map(c -> market.findCloses(c.getId(), LocalDate.now().minus(range.period))));
    }

    /** La séance du jour, un point toutes les 5 minutes. */
    @GetMapping("/api/companies/{isin}/prices/intraday")
    public ResponseEntity<IntradaySource.Intraday> getIntraday(@PathVariable String isin) {
        return ResponseEntity.of(companies.findByIsin(isin).flatMap(c -> market.fetchIntraday(c.getId())));
    }
}
