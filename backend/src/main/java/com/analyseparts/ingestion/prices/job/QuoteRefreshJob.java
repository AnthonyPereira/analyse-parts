package com.analyseparts.ingestion.prices.job;

import com.analyseparts.ingestion.prices.service.QuoteRefreshService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * Les collectes planifiées de {@link QuoteRefreshService} :
 * <ul>
 *   <li>cours : chaque minute pendant la séance ;</li>
 *   <li>historique : chaque soir de bourse à 18 h 15 ;</li>
 *   <li>taux de change : chaque jour ouvré à 16 h 30.</li>
 * </ul>
 */
@Component
class QuoteRefreshJob {

    private final QuoteRefreshService quotes;

    QuoteRefreshJob(QuoteRefreshService quotes) {
        this.quotes = quotes;
    }

    @Scheduled(fixedDelayString = "${analyse-parts.prices.live-interval-ms:60000}", initialDelay = 30_000)
    void refreshLiveQuotes() {
        if (QuoteRefreshService.isMarketHours(ZonedDateTime.now(QuoteRefreshService.PARIS))) {
            quotes.refreshQuotes();
        }
    }

    @Scheduled(cron = "${analyse-parts.prices.history-cron:0 15 18 * * MON-FRI}", zone = "Europe/Paris")
    void refreshHistory() {
        quotes.refreshHistory(false);
    }

    @Scheduled(cron = "${analyse-parts.fx.cron:0 30 16 * * MON-FRI}", zone = "Europe/Paris")
    void refreshFxRates() {
        quotes.refreshFxRates();
    }
}
