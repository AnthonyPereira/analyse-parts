package com.analyseparts.ingestion.esef;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rafraîchissement automatique des rapports annuels : les deux sources chaque lundi à 3 h,
 * l'AMF seule les autres jours.
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

    /**
     * Les autres jours, à 7 h (heure de Paris), l'AMF seulement : un rapport déposé la veille est
     * pris dès le lendemain matin. Désactivable avec {@code analyse-parts.esef.amf-refresh-cron=-}.
     */
    @Scheduled(cron = "${analyse-parts.esef.amf-refresh-cron:0 0 7 * * TUE-SUN}", zone = "Europe/Paris")
    void refreshFromAmf() {
        esefImport.refreshFromAmf();
    }
}
