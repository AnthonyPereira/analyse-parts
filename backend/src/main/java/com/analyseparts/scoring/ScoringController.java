package com.analyseparts.scoring;

import com.analyseparts.scoring.core.Profile;
import com.analyseparts.scoring.core.Ratio;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioFamily;
import com.analyseparts.scoring.core.Scale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * API du score.
 * <ul>
 *   <li>{@code GET /api/scoring/ratios} et {@code /profiles} : le référentiel (définitions, barèmes,
 *       poids), pour que le front puisse tout expliquer ;</li>
 *   <li>{@code GET /api/companies/{isin}/ratios} : les ratios d'une société ;</li>
 *   <li>{@code GET /api/companies/{isin}/score?profile=QUALITY} : son score détaillé ;</li>
 *   <li>{@code GET /api/scores?profile=QUALITY} : le classement.</li>
 * </ul>
 */
@RestController
public class ScoringController {

    private final ScoringService scoring;

    public ScoringController(ScoringService scoring) {
        this.scoring = scoring;
    }

    public record RatioDefinition(Ratio id, RatioFamily family, String familyLabel, String label,
                                  String description, Ratio.Unit unit, List<Scale.Anchor> scale) {
    }

    public record ProfileDefinition(Profile id, String label, String description, Map<Ratio, Integer> weights) {
    }

    @GetMapping("/api/scoring/ratios")
    public List<RatioDefinition> ratios() {
        return Arrays.stream(Ratio.values())
                .map(r -> new RatioDefinition(r, r.family(), r.family().label(), r.label(), r.description(),
                        r.unit(), r.scale().anchors()))
                .toList();
    }

    @GetMapping("/api/scoring/profiles")
    public List<ProfileDefinition> profiles() {
        return Arrays.stream(Profile.values())
                .map(p -> new ProfileDefinition(p, p.label(), p.description(), p.weights()))
                .toList();
    }

    @GetMapping("/api/companies/{isin}/ratios")
    public ResponseEntity<RatioSet> companyRatios(@PathVariable String isin) {
        return ResponseEntity.of(scoring.ratios(isin));
    }

    @GetMapping("/api/companies/{isin}/score")
    public ResponseEntity<ScoringService.CompanyScore> companyScore(
            @PathVariable String isin, @RequestParam(defaultValue = "QUALITY") Profile profile) {
        return ResponseEntity.of(scoring.score(isin, profile));
    }

    @GetMapping("/api/scores")
    public List<ScoringService.RankingEntry> ranking(@RequestParam(defaultValue = "QUALITY") Profile profile) {
        return scoring.ranking(profile);
    }
}
