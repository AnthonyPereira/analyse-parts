package com.analyseparts.ingestion.esef.job;

import com.analyseparts.ingestion.esef.service.EsefImportService;
import com.analyseparts.ingestion.esef.service.EsefIssuerImportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rafraîchissement automatique des rapports annuels : les deux sources chaque lundi à 3 h,
 * l'AMF seule les autres jours, et la recherche des nouveaux émetteurs le lundi à 4 h 30.
 * Désactivable avec {@code analyse-parts.esef.refresh-cron=-}.
 */
@Component
class EsefRefreshScheduler {

    private final EsefImportService esefImport;
    private final EsefIssuerImportService issuerImport;

    EsefRefreshScheduler(EsefImportService esefImport, EsefIssuerImportService issuerImport) {
        this.esefImport = esefImport;
        this.issuerImport = issuerImport;
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

    /** Chaque semaine : les nouveaux émetteurs de l'index (une centaine de requêtes, sauf au premier passage). */
    @Scheduled(cron = "${analyse-parts.esef.issuers-cron:0 30 4 * * MON}", zone = "Europe/Paris")
    void importIssuers() {
        issuerImport.importIssuers(Integer.MAX_VALUE);
    }
}
