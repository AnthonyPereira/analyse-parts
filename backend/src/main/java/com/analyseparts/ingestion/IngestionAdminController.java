package com.analyseparts.ingestion;

import com.analyseparts.ingestion.esef.EsefImportService;
import com.analyseparts.ingestion.euronext.EuronextImportService;
import com.analyseparts.ingestion.euronext.FactsheetEnrichmentService;
import com.analyseparts.ingestion.euronext.EuronextImportService.ImportResult;
import com.analyseparts.ingestion.prices.QuoteRefreshService;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService.EnrichmentResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Déclenchement manuel des imports (les mêmes tâches tournent aussi seules, planifiées).
 */
@RestController
@RequestMapping("/api/admin/ingestion")
public class IngestionAdminController {

    private final EuronextImportService euronextImport;
    private final LeiEnrichmentService leiEnrichment;
    private final EsefImportService esefImport;
    private final FactsheetEnrichmentService factsheets;
    private final QuoteRefreshService prices;

    public IngestionAdminController(EuronextImportService euronextImport, LeiEnrichmentService leiEnrichment,
                                    EsefImportService esefImport, FactsheetEnrichmentService factsheets,
                                    QuoteRefreshService prices) {
        this.prices = prices;
        this.factsheets = factsheets;
        this.euronextImport = euronextImport;
        this.leiEnrichment = leiEnrichment;
        this.esefImport = esefImport;
    }

    /** Ex. : POST /api/admin/ingestion/euronext?mic=XPAR */
    @PostMapping("/euronext")
    public ImportResult importEuronext(@RequestParam(defaultValue = "XPAR") String mic) {
        return euronextImport.importMarket(mic);
    }

    /** Cours (Yahoo) et taux de change (BCE). Aussi lancés automatiquement pendant la séance. */
    @PostMapping("/prices")
    public QuoteRefreshService.RefreshResult refreshPrices() {
        prices.refreshFxRates();
        return prices.refreshQuotes();
    }

    /**
     * Historique des clôtures (Yahoo) : 5 ans pour les sociétés qui n'en ont pas, sinon les
     * 5 derniers jours ; {@code full=true} recharge 5 ans pour toutes (≈ 2 min). Aussi lancé chaque soir.
     */
    @PostMapping("/price-history")
    public QuoteRefreshService.HistoryResult refreshPriceHistory(@RequestParam(defaultValue = "false") boolean full) {
        return prices.refreshHistory(full);
    }

    /** Lit la fiche Euronext (secteur ICB, nombre d'actions) des sociétés jamais lues ou lues il y a plus d'un mois. */
    @PostMapping("/factsheets")
    public FactsheetEnrichmentService.EnrichmentResult enrichFactsheets() {
        return factsheets.enrichDue();
    }

    /** Recherche le LEI des sociétés jamais vérifiées (≈ 1 s par société). */
    @PostMapping("/lei")
    public EnrichmentResult enrichLei() {
        return leiEnrichment.enrichMissingLeis();
    }

    /**
     * Rapports annuels ESEF.
     * Ex. : POST /api/admin/ingestion/esef?isin=FR0000120271 (une société)
     *       POST /api/admin/ingestion/esef?limit=10 (10 sociétés sans rapport)
     */
    @PostMapping("/esef")
    public EsefImportService.ImportResult importEsef(@RequestParam(required = false) String isin,
                                                     @RequestParam(defaultValue = "10") int limit) {
        return isin != null ? esefImport.importCompany(isin) : esefImport.importMissing(limit);
    }

    /**
     * Un paquet ESEF publié ailleurs (site de la société), par son adresse.
     * Ex. : POST /api/admin/ingestion/esef/package?isin=LU0569974404&url=https://www.aperam.com/…zip
     */
    @PostMapping("/esef/package")
    public EsefImportService.ImportResult importEsefPackage(@RequestParam String isin, @RequestParam String url) {
        return esefImport.importPackage(isin, url);
    }

    /**
     * Cherche les nouveaux rapports des sociétés dont un rapport plus récent est attendu.
     * Ex. : POST /api/admin/ingestion/esef/refresh (aussi lancé chaque lundi à 3 h).
     * {@code source=amf} : n'interroge que l'AMF.
     */
    @PostMapping("/esef/refresh")
    public EsefImportService.ImportResult refreshEsef(@RequestParam(defaultValue = "1000") int limit,
                                                      @RequestParam(defaultValue = "all") String source) {
        return esefImport.refresh(limit, true, !"amf".equals(source));
    }
}
