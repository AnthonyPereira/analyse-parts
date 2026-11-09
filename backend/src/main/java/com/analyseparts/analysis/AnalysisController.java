package com.analyseparts.analysis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AnalysisController {

    private final AnalysisService analyses;
    private final HalfYearService halfYears;

    public AnalysisController(AnalysisService analyses, HalfYearService halfYears) {
        this.analyses = analyses;
        this.halfYears = halfYears;
    }

    /** Le dernier rapport semestriel lu et les chiffres sur 12 mois glissants. */
    @GetMapping("/api/companies/{isin}/half-year")
    public ResponseEntity<HalfYearView> halfYear(@PathVariable String isin) {
        return ResponseEntity.of(halfYears.view(isin));
    }

    /** Relit le rapport semestriel d'une société (réponse immédiate, lecture en arrière-plan). */
    @PostMapping("/api/admin/half-year/{isin}")
    public ResponseEntity<HalfYearView> readHalfYear(@PathVariable String isin) {
        return halfYears.request(isin).map(v -> ResponseEntity.accepted().body(v))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Lit chaque rapport semestriel attendu et pas encore lu. */
    @PostMapping("/api/admin/half-year/missing")
    public Map<String, Integer> readMissingHalfYears() {
        return Map.of("queued", halfYears.requestMissing());
    }

    /** La dernière lecture du rapport annuel d'une société, et l'état de la file. */
    @GetMapping("/api/companies/{isin}/analysis")
    public ResponseEntity<AnalysisView> analysis(@PathVariable String isin) {
        return ResponseEntity.of(analyses.view(isin));
    }

    /** Relit le dernier rapport d'une société (réponse immédiate, lecture en arrière-plan). */
    @PostMapping("/api/admin/analysis/{isin}")
    public ResponseEntity<AnalysisView> analyse(@PathVariable String isin) {
        return analyses.request(isin).map(v -> ResponseEntity.accepted().body(v))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Lit chaque dernier rapport pas encore lu : plusieurs heures pour toute la cote. */
    @PostMapping("/api/admin/analysis/missing")
    public Map<String, Integer> analyseMissing() {
        return Map.of("queued", analyses.requestMissing());
    }
}
