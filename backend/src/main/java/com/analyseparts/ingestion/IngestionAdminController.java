package com.analyseparts.ingestion;

import java.util.List;
import com.analyseparts.ingestion.esef.EsefImportService;
import com.analyseparts.ingestion.esef.EsefIssuerImportService;
import com.analyseparts.ingestion.euronext.EuronextImportService;
import com.analyseparts.ingestion.euronext.FactsheetEnrichmentService;
import com.analyseparts.ingestion.euronext.EuronextImportService.ImportResult;
import com.analyseparts.ingestion.prices.QuoteRefreshService;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService.EnrichmentResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final EsefIssuerImportService issuers;

    public IngestionAdminController(EuronextImportService euronextImport, LeiEnrichmentService leiEnrichment,
                                    EsefImportService esefImport, FactsheetEnrichmentService factsheets,
                                    QuoteRefreshService prices, EsefIssuerImportService issuers) {
        this.issuers = issuers;
        this.prices = prices;
        this.factsheets = factsheets;
        this.euronextImport = euronextImport;
        this.leiEnrichment = leiEnrichment;
        this.esefImport = esefImport;
    }

    /**
     * Sociétés cotées hors Euronext, trouvées par leur rapport ESEF puis chez Yahoo (ADR 0020).
     * Ex. : POST /api/admin/ingestion/esef-issuers?limit=100 (environ 3 s par émetteur examiné).
     */
    @PostMapping("/esef-issuers")
    public EsefIssuerImportService.IssuerImportResult importEsefIssuers(
            @RequestParam(defaultValue = "100") int limit) {
        return issuers.importIssuers(limit);
    }

    /**
     * Réapplique la table yahoo-icb.csv aux sociétés hors Euronext, après une correction de la table
     * (environ 1 s par société, une vingtaine de minutes en tout). Lancée en arrière-plan : 202 tout
     * de suite, 409 si une relance est en cours. Suivi : GET sur la même adresse ; une fois
     * {@code running} faux, recalibrer : POST /api/admin/scoring/calibrate.
     */
    @PostMapping("/esef-issuers/sectors")
    public ResponseEntity<EsefIssuerImportService.SectorUpdateStatus> updateSectors() {
        boolean started = issuers.startSectorUpdate();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(issuers.getSectorUpdateStatus());
    }

    @GetMapping("/esef-issuers/sectors")
    public EsefIssuerImportService.SectorUpdateStatus getSectorUpdateStatus() {
        return issuers.getSectorUpdateStatus();
    }

    /** Ex. : POST /api/admin/ingestion/euronext?mic=XPAR ; sans paramètre, les 7 marchés (ADR 0020). */
    @PostMapping("/euronext")
    public List<ImportResult> importEuronext(@RequestParam(required = false) String mic) {
        if (mic == null || mic.isBlank()) {
            return euronextImport.importAllMarkets();
        }
        if (StockExchange.findByMic(mic).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Marché non importé : " + mic);
        }
        return List.of(euronextImport.importMarket(mic));
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
