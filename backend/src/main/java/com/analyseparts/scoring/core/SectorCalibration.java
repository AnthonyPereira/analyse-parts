package com.analyseparts.scoring.core;

import com.analyseparts.company.Icb;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Le niveau « normal » de chaque ratio dans chaque secteur, pour adapter les barèmes.
 * <p>
 * Principe : on calcule la <b>médiane</b> d'un ratio dans un secteur (la valeur du milieu : la
 * moitié des sociétés fait mieux, la moitié moins bien) et dans tout l'univers. Leur rapport
 * donne un <b>facteur</b> qui étire ou resserre le barème général :
 * <pre>
 * marge nette médiane : distribution 2,4 %, univers 6,0 %  →  facteur 0,4
 * barème général    0 % → 0, 5 % → 40, 10 % → 70, 20 % → 100
 * barème ajusté     0 % → 0, 2 % → 40,  4 % → 70,  8 % → 100
 * </pre>
 * Une marge de 4 % vaut 70 points pour un distributeur, 32 points pour les autres.
 * <p>
 * Garde-fous :
 * <ul>
 *   <li>on prend la médiane du super-secteur ICB (ex. « Retail ») dès qu'au moins
 *       {@value #MIN_SUPERSECTOR_SAMPLE} de ses sociétés ont le ratio : même un petit super-secteur
 *       (assurance, 3 sociétés à Paris) garde sa propre référence. En dessous, une « médiane » de 1 ou 2
 *       sociétés serait la société elle-même : on passe à l'industrie (ex. « Financials ») s'il y en
 *       a au moins {@value #MIN_SAMPLE}, sinon au barème général ;</li>
 *   <li>facteur borné entre {@value #MIN_FACTOR} et {@value #MAX_FACTOR} : un secteur entier
 *       en difficulté ne doit pas devenir la norme ;</li>
 *   <li>la calibration est calculée à un instant donné puis figée : le score d'une société ne
 *       bouge pas parce qu'une autre a publié ses comptes (cf. ADR 0003 et 0004).</li>
 * </ul>
 */
public final class SectorCalibration {

    /** Minimum pour une médiane d'industrie ou de l'univers. */
    public static final int MIN_SAMPLE = 8;
    /** Minimum pour une médiane de super-secteur (choix : garder chaque super-secteur, même petit). */
    public static final int MIN_SUPERSECTOR_SAMPLE = 3;
    public static final double MIN_FACTOR = 0.4;
    public static final double MAX_FACTOR = 2.5;

    public static final String UNIVERSE = "ALL";
    public static final String SUPERSECTOR = "SUPERSECTOR";
    public static final String INDUSTRY = "INDUSTRY";

    /** Une médiane : pour un niveau (ALL, SUPERSECTOR, INDUSTRY) et un code ICB. */
    public record Median(String level, String code, String label, Ratio ratio, double median, int sampleSize) {
    }

    /** L'ajustement appliqué au barème d'un ratio pour une société, avec de quoi l'expliquer. */
    public record Adjustment(String level, String code, String label, double factor, double sectorMedian,
                             double universeMedian, int sampleSize) {
    }

    /** Les ratios d'une société et son secteur, pour calculer la calibration. */
    public record Sample(Icb icb, RatioSet ratios) {
    }

    private final Map<String, Median> medians; // clé : level|code|ratio
    private final Instant calibratedAt;

    private SectorCalibration(List<Median> medians, Instant calibratedAt) {
        this.medians = new HashMap<>();
        for (Median m : medians) {
            this.medians.put(buildKey(m.level(), m.code(), m.ratio()), m);
        }
        this.calibratedAt = calibratedAt;
    }

    /** Recharge une calibration enregistrée. */
    public static SectorCalibration of(List<Median> medians, Instant calibratedAt) {
        return new SectorCalibration(medians, calibratedAt);
    }

    /** Pas de calibration : tous les barèmes restent généraux. */
    public static SectorCalibration createEmpty() {
        return new SectorCalibration(List.of(), null);
    }

    /** Calcule les médianes par super-secteur, par industrie et pour tout l'univers. */
    public static SectorCalibration compute(List<Sample> samples, Instant now) {
        List<Median> result = new ArrayList<>();
        for (Ratio ratio : Ratio.values()) {
            if (!ratio.isSectorAdjusted()) {
                continue;
            }
            Map<String, List<Double>> bySupersector = new HashMap<>();
            Map<String, List<Double>> byIndustry = new HashMap<>();
            Map<String, String> labels = new HashMap<>();
            List<Double> all = new ArrayList<>();
            for (Sample s : samples) {
                var value = s.ratios().get(ratio);
                if (value.isEmpty()) {
                    continue;
                }
                double v = value.get().value();
                all.add(v);
                if (s.icb() != null) {
                    bySupersector.computeIfAbsent(s.icb().extractSupersectorCode(), _ -> new ArrayList<>()).add(v);
                    byIndustry.computeIfAbsent(s.icb().extractIndustryCode(), _ -> new ArrayList<>()).add(v);
                    labels.put(SUPERSECTOR + s.icb().extractSupersectorCode(), s.icb().supersector());
                    labels.put(INDUSTRY + s.icb().extractIndustryCode(), s.icb().industry());
                }
            }
            if (all.size() < MIN_SAMPLE) {
                continue;
            }
            result.add(new Median(UNIVERSE, UNIVERSE, "Toutes les sociétés", ratio, computeMedian(all), all.size()));
            bySupersector.forEach((code, values) -> {
                if (values.size() >= MIN_SUPERSECTOR_SAMPLE) {
                    result.add(new Median(SUPERSECTOR, code, labels.get(SUPERSECTOR + code), ratio, computeMedian(values), values.size()));
                }
            });
            byIndustry.forEach((code, values) -> {
                if (values.size() >= MIN_SAMPLE) {
                    result.add(new Median(INDUSTRY, code, labels.get(INDUSTRY + code), ratio, computeMedian(values), values.size()));
                }
            });
        }
        result.sort(Comparator.comparing(Median::level).thenComparing(Median::code).thenComparing(Median::ratio));
        return new SectorCalibration(result, now);
    }

    /** L'ajustement du barème d'un ratio pour une société de ce secteur, s'il y en a un. */
    public Optional<Adjustment> findAdjustment(Ratio ratio, Icb icb) {
        if (!ratio.isSectorAdjusted() || icb == null) {
            return Optional.empty();
        }
        Median universe = medians.get(buildKey(UNIVERSE, UNIVERSE, ratio));
        if (universe == null || universe.median() <= 0) {
            return Optional.empty();
        }
        Median sector = Optional.ofNullable(medians.get(buildKey(SUPERSECTOR, icb.extractSupersectorCode(), ratio)))
                .orElse(medians.get(buildKey(INDUSTRY, icb.extractIndustryCode(), ratio)));
        if (sector == null) {
            return Optional.empty();
        }
        // Médiane du secteur négative ou nulle (ex. secteur sans dette nette) : facteur minimal.
        double factor = sector.median() <= 0 ? MIN_FACTOR
                : Math.clamp(sector.median() / universe.median(), MIN_FACTOR, MAX_FACTOR);
        return Optional.of(new Adjustment(sector.level(), sector.code(), sector.label(), factor,
                sector.median(), universe.median(), sector.sampleSize()));
    }

    public List<Median> getMedians() {
        return medians.values().stream()
                .sorted(Comparator.comparing(Median::level).thenComparing(Median::code).thenComparing(Median::ratio))
                .toList();
    }

    public Optional<Instant> getCalibratedAt() {
        return Optional.ofNullable(calibratedAt);
    }

    static double computeMedian(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static String buildKey(String level, String code, Ratio ratio) {
        return level + "|" + code + "|" + ratio;
    }
}
