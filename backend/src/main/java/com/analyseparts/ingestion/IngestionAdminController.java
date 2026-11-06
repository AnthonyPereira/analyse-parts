package com.analyseparts.ingestion;

import com.analyseparts.ingestion.esef.EsefImportService;
import com.analyseparts.ingestion.euronext.EuronextImportService;
import com.analyseparts.ingestion.euronext.EuronextImportService.ImportResult;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService;
import com.analyseparts.ingestion.gleif.LeiEnrichmentService.EnrichmentResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Déclenchement manuel des imports. Provisoire : en Phase 4, ces étapes seront
 * enchaînées par des événements Kafka.
 */
@RestController
@RequestMapping("/api/admin/ingestion")
public class IngestionAdminController {

    private final EuronextImportService euronextImport;
    private final LeiEnrichmentService leiEnrichment;
    private final EsefImportService esefImport;

    public IngestionAdminController(EuronextImportService euronextImport, LeiEnrichmentService leiEnrichment,
                                    EsefImportService esefImport) {
        this.euronextImport = euronextImport;
        this.leiEnrichment = leiEnrichment;
        this.esefImport = esefImport;
    }

    /** Ex. : POST /api/admin/ingestion/euronext?mic=XPAR */
    @PostMapping("/euronext")
    public ImportResult importEuronext(@RequestParam(defaultValue = "XPAR") String mic) {
        return euronextImport.importMarket(mic);
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
}
