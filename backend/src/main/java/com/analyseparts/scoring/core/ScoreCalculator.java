package com.analyseparts.scoring.core;

import com.analyseparts.scoring.core.RatioCalculator.Input;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioCalculator.RatioValue;
import com.analyseparts.scoring.core.SectorCalibration.Adjustment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Calcule le score de 0 à 100 d'une société pour des poids donnés.
 * <ol>
 *   <li>chaque ratio est converti en points (0 à 100) par son barème ({@link Scale}) ;</li>
 *   <li>le score est la moyenne des points, pondérée par les poids du profil ;</li>
 *   <li>un ratio indisponible est retiré et son poids réparti sur les autres
 *       (la moyenne se fait sur les poids disponibles) ;</li>
 *   <li>si moins de {@value #MIN_COVERAGE_PERCENT} % du poids est disponible, pas de score :
 *       il ne reposerait pas sur assez d'éléments.</li>
 *   <li>si les chiffres sont trop anciens ({@link Freshness#OBSOLETE}), pas de score non plus.</li>
 * </ol>
 * Chaque contribution est conservée : le score s'explique ratio par ratio.
 */
public final class ScoreCalculator {

    public static final int MIN_COVERAGE_PERCENT = 60;

    /**
     * Ce qu'un ratio apporte au score.
     *
     * @param points       points obtenus (0 à 100) d'après le barème
     * @param weight       poids prévu par le profil
     * @param share        poids réel une fois les ratios indisponibles retirés (0 à 1)
     * @param contribution points × part réelle : la somme des contributions donne le score
     * @param sectorAdjustment barème adapté au secteur (null : barème général)
     */
    public record Contribution(Ratio ratio, double value, int points, int weight, double share,
                               double contribution, Adjustment sectorAdjustment, List<Input> inputs) {
    }

    public record Missing(Ratio ratio, int weight, String reason) {
    }

    /**
     * @param score       0 à 100, ou {@code null} si les données ne suffisent pas
     * @param coverage    part du poids du profil réellement calculée (0 à 1)
     * @param freshness   fraîcheur des chiffres (score signalé si en retard, absent si obsolète)
     * @param familyScore points moyens par famille de ratios (pour voir les forces et faiblesses)
     */
    public record Score(Integer score, double coverage, LocalDate fiscalYearEnd, Freshness freshness,
                        Map<RatioFamily, Integer> familyScore, List<Contribution> contributions, List<Missing> missing) {
    }

    private ScoreCalculator() {
    }

    /** Barèmes généraux, sans ajustement sectoriel. */
    public static Score score(RatioSet ratios, Map<Ratio, Integer> weights, LocalDate asOf) {
        return score(ratios, weights, asOf, _ -> Optional.empty());
    }

    /**
     * @param asOf        date du calcul, pour juger de la fraîcheur des chiffres
     * @param adjustments ajustement sectoriel du barème de chaque ratio ({@link SectorCalibration})
     */
    public static Score score(RatioSet ratios, Map<Ratio, Integer> weights, LocalDate asOf,
                              Function<Ratio, Optional<Adjustment>> adjustments) {
        int totalWeight = 0;
        int availableWeight = 0;
        for (var entry : weights.entrySet()) {
            if (entry.getValue() > 0) {
                totalWeight += entry.getValue();
                if (ratios.get(entry.getKey()).isPresent()) {
                    availableWeight += entry.getValue();
                }
            }
        }

        List<Contribution> contributions = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();
        Map<RatioFamily, double[]> families = new EnumMap<>(RatioFamily.class); // [Σ points × poids, Σ poids]
        double total = 0;
        for (var entry : weights.entrySet()) {
            Ratio ratio = entry.getKey();
            int weight = entry.getValue();
            if (weight <= 0) {
                continue;
            }
            var value = ratios.get(ratio);
            if (value.isEmpty()) {
                missing.add(new Missing(ratio, weight, ratios.unavailable().getOrDefault(ratio, "Non calculé")));
                continue;
            }
            RatioValue rv = value.get();
            Adjustment adjustment = adjustments.apply(ratio).orElse(null);
            Scale scale = adjustment == null ? ratio.scale() : ratio.scale().scaled(adjustment.factor());
            double points = scale.points(rv.value());
            double share = (double) weight / availableWeight;
            total += points * share;
            contributions.add(new Contribution(ratio, rv.value(), (int) Math.round(points), weight, share,
                    points * share, adjustment, rv.inputs()));
            double[] f = families.computeIfAbsent(ratio.family(), _ -> new double[2]);
            f[0] += points * weight;
            f[1] += weight;
        }

        contributions.sort(Comparator.comparingDouble(Contribution::contribution).reversed());
        missing.sort(Comparator.comparingInt(Missing::weight).reversed());
        Map<RatioFamily, Integer> familyScore = new EnumMap<>(RatioFamily.class);
        families.forEach((family, f) -> familyScore.put(family, (int) Math.round(f[0] / f[1])));

        double coverage = totalWeight == 0 ? 0 : (double) availableWeight / totalWeight;
        Freshness freshness = Freshness.of(ratios.fiscalYearEnd(), asOf);
        boolean enoughData = coverage * 100 >= MIN_COVERAGE_PERCENT && freshness != Freshness.OBSOLETE;
        Integer score = enoughData ? (int) Math.round(total) : null;
        return new Score(score, coverage, ratios.fiscalYearEnd(), freshness, familyScore, contributions, missing);
    }
}
