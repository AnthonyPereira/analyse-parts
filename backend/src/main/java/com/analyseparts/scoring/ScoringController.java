package com.analyseparts.scoring;

import com.analyseparts.scoring.core.Profile;
import com.analyseparts.scoring.core.Ratio;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioFamily;
import com.analyseparts.scoring.core.Scale;
import com.analyseparts.scoring.core.SectorCalibration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * API du score.
 * <ul>
 *   <li>{@code GET /api/scoring/ratios} et {@code /profiles} : le référentiel (définitions, barèmes,
 *       poids), pour que le front puisse tout expliquer ;</li>
 *   <li>{@code GET /api/companies/{isin}/ratios} : les ratios d'une société ;</li>
 *   <li>{@code GET /api/companies/{isin}/score?profile=QUALITY} : son score détaillé ;</li>
 *   <li>{@code GET /api/scores?profile=QUALITY} : le classement ;</li>
 *   <li>{@code GET /api/companies/{isin}/score-history} : l'historique des 4 notes ;</li>
 *   <li>{@code GET /api/scoring/calibration} : les médianes par secteur ({@code POST /api/admin/scoring/calibrate} pour recalculer).</li>
 * </ul>
 */
@RestController
public class ScoringController {

    private final ScoringService scoring;
    private final ScoreHistoryService scoreHistory;

    public ScoringController(ScoringService scoring, ScoreHistoryService scoreHistory) {
        this.scoring = scoring;
        this.scoreHistory = scoreHistory;
    }

    public record RatioDefinition(Ratio id, RatioFamily family, String familyLabel, String label,
                                  String description, Ratio.Unit unit, boolean sectorAdjusted,
                                  List<Scale.Anchor> scale) {
    }

    public record ProfileDefinition(Profile id, String label, String description, Map<Ratio, Integer> weights,
                                    Map<Ratio, Integer> bankWeights, Map<Ratio, Integer> insurerWeights) {
    }

    @GetMapping("/api/scoring/ratios")
    public List<RatioDefinition> listRatios() {
        return Arrays.stream(Ratio.values())
                .map(r -> new RatioDefinition(r, r.getFamily(), r.getFamily().getLabel(), r.getLabel(), r.getDescription(),
                        r.getUnit(), r.isSectorAdjusted(), r.getScale().anchors()))
                .toList();
    }

    @GetMapping("/api/scoring/profiles")
    public List<ProfileDefinition> listProfiles() {
        return Arrays.stream(Profile.values())
                .map(p -> new ProfileDefinition(p, p.getLabel(), p.getDescription(), p.getWeights(), p.getBankWeights(), p.getInsurerWeights()))
                .toList();
    }

    public record CalibrationView(Instant calibratedAt, List<SectorCalibration.Median> medians) {
    }

    /** Les médianes par secteur utilisées pour adapter les barèmes. */
    @GetMapping("/api/scoring/calibration")
    public CalibrationView getCalibration() {
        SectorCalibration c = scoring.getCalibration();
        return new CalibrationView(c.getCalibratedAt().orElse(null), c.getMedians());
    }

    /** Recalcule et fige les médianes par secteur (à relancer après un gros import). */
    @PostMapping("/api/admin/scoring/calibrate")
    public CalibrationView recalibrate() {
        SectorCalibration c = scoring.recalibrate();
        return new CalibrationView(c.getCalibratedAt().orElse(null), c.getMedians());
    }

    @GetMapping("/api/companies/{isin}/ratios")
    public ResponseEntity<RatioSet> getCompanyRatios(@PathVariable String isin) {
        return ResponseEntity.of(scoring.computeRatios(isin));
    }

    @GetMapping("/api/companies/{isin}/score")
    public ResponseEntity<ScoringService.CompanyScore> getCompanyScore(
            @PathVariable String isin, @RequestParam(defaultValue = "QUALITY") Profile profile) {
        return ResponseEntity.of(scoring.score(isin, profile));
    }

    @GetMapping("/api/scores")
    public List<ScoringService.RankingEntry> getRanking(@RequestParam(defaultValue = "QUALITY") Profile profile) {
        return scoring.computeRanking(profile);
    }

    /**
     * Mode avancé : classement avec des poids choisis par l'utilisateur.
     * Ex. : POST /api/scores/custom {"ROE": 50, "PER": 50}. Les poids sont relatifs (pas besoin
     * que leur somme fasse 100).
     */
    @PostMapping("/api/scores/custom")
    public ResponseEntity<List<ScoringService.RankingEntry>> computeCustomRanking(@RequestBody Map<Ratio, Integer> weights) {
        Map<Ratio, Integer> positive = new EnumMap<>(Ratio.class);
        weights.forEach((ratio, weight) -> {
            if (weight != null && weight > 0) {
                positive.put(ratio, Math.min(weight, 100));
            }
        });
        if (positive.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(scoring.computeRanking(positive));
    }

    /** Toutes les sociétés avec leurs ratios et leurs 4 scores, pour les graphiques de comparaison. */
    @GetMapping("/api/universe")
    public List<ScoringService.UniverseEntry> getUniverse() {
        return scoring.buildUniverse();
    }

    /** Les ratios exercice par exercice, avec la valorisation à chaque date de clôture. */
    @GetMapping("/api/companies/{isin}/ratios/history")
    public ResponseEntity<List<ScoringService.YearRatios>> getRatioHistory(@PathVariable String isin) {
        return ResponseEntity.of(scoring.computeRatioHistory(isin));
    }

    /** L'historique des 4 notes d'une société, un point par jour. */
    @GetMapping("/api/companies/{isin}/score-history")
    public ResponseEntity<List<ScoreHistoryService.Point>> getScoreHistory(@PathVariable String isin) {
        return ResponseEntity.of(scoreHistory.findHistory(isin));
    }

    /** Prend tout de suite la photo quotidienne des notes (sans attendre 18 h 45). */
    @PostMapping("/api/admin/scoring/history/snapshot")
    public Map<String, Integer> recordSnapshot() {
        return Map.of("companies", scoreHistory.recordDaily());
    }
}
