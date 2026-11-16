package com.analyseparts.ingestion.euronext.job;

import com.analyseparts.ingestion.euronext.service.FactsheetEnrichmentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tous les jours à 4 h : relit les fiches Euronext à rafraîchir ({@link FactsheetEnrichmentService}). */
@Component
class FactsheetEnrichmentJob {

    private final FactsheetEnrichmentService factsheets;

    FactsheetEnrichmentJob(FactsheetEnrichmentService factsheets) {
        this.factsheets = factsheets;
    }

    @Scheduled(cron = "${analyse-parts.euronext.factsheet-cron:0 0 4 * * *}")
    void enrich() {
        factsheets.enrichDue();
    }
}
