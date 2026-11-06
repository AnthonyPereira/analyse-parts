package com.analyseparts.market;

import com.analyseparts.company.CompanyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/** Le dernier cours connu d'une société. Ex. : GET /api/companies/FR0000121014/quote */
@RestController
public class QuoteController {

    private final CompanyRepository companies;
    private final MarketDataService market;

    public QuoteController(CompanyRepository companies, MarketDataService market) {
        this.companies = companies;
        this.market = market;
    }

    public record QuoteView(BigDecimal price, String currency, Instant asOf, String source) {
    }

    @GetMapping("/api/companies/{isin}/quote")
    public ResponseEntity<QuoteView> quote(@PathVariable String isin) {
        return ResponseEntity.of(companies.findByIsin(isin)
                .flatMap(c -> market.latestQuote(c.getId()))
                .map(q -> new QuoteView(q.price(), q.currency(), q.asOf(), q.source())));
    }
}
