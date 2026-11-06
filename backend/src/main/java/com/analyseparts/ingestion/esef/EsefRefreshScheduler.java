package com.analyseparts.ingestion.esef;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rafraîchissement automatique des rapports annuels, chaque lundi à 3 h par défaut.
 * Désactivable avec {@code analyse-parts.esef.refresh-cron=-}.
 */
@Component
class EsefRefreshScheduler {

    private final EsefImportService esefImport;

    EsefRefreshScheduler(EsefImportService esefImport) {
        this.esefImport = esefImport;
    }

    @Scheduled(cron = "${analyse-parts.esef.refresh-cron:0 0 3 * * MON}")
    void refresh() {
        esefImport.refresh(Integer.MAX_VALUE, false);
    }
}
