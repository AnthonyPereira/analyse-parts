package com.analyseparts.ingestion.prices;

import com.analyseparts.company.CompanyRepository;
import com.analyseparts.market.IntradaySource;
import com.analyseparts.market.PricePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/** Implémentation du port {@link IntradaySource} du module market, avec Yahoo. */
@Component
class YahooIntradaySource implements IntradaySource {

    private static final Logger log = LoggerFactory.getLogger(YahooIntradaySource.class);

    private final CompanyRepository companies;
    private final QuoteRefreshService prices;

    YahooIntradaySource(CompanyRepository companies, QuoteRefreshService prices) {
        this.companies = companies;
        this.prices = prices;
    }

    @Override
    public Optional<Intraday> fetchIntraday(long companyId) {
        try {
            return companies.findById(companyId)
                    .flatMap(prices::fetchIntraday)
                    .map(chart -> new Intraday(chart.currency(), chart.previousClose(), chart.points().stream()
                            .map(p -> new PricePoint(p.time(), p.close(), p.volume()))
                            .toList()));
        } catch (RestClientException e) {
            log.warn("Yahoo : séance du jour indisponible pour la société {} : {}", companyId, e.getMessage());
            return Optional.empty();
        }
    }
}
