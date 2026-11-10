package com.analyseparts.financials;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Calcule les indicateurs annuels à partir des faits bruts de plusieurs rapports.
 * <p>
 * Java pur, sans Spring ni base de données : facile à tester et à faire évoluer.
 * <p>
 * Règles :
 * <ol>
 *   <li>un indicateur de flux doit couvrir un exercice complet (350 à 380 jours) ;
 *       un indicateur de bilan doit être une valeur à la date de clôture ;</li>
 *   <li>l'unité doit correspondre (devise, ou devise par action pour le BPA) ;</li>
 *   <li>les concepts candidats sont essayés dans l'ordre, le premier trouvé l'emporte ;</li>
 *   <li>si plusieurs rapports donnent la valeur (un rapport reprend les chiffres de l'année
 *       précédente), on garde celle du <b>rapport le plus récent</b> : elle intègre les
 *       éventuels retraitements.</li>
 * </ol>
 */
public final class MetricExtractor {

    private static final int MIN_YEAR_DAYS = 350;
    private static final int MAX_YEAR_DAYS = 380;
    private static final String PER_SHARE_SUFFIX = "/xbrli:shares";

    /** Un fait, avec le rapport dont il provient. */
    public record SourcedFact(ReportedFact fact, String filing, LocalDate filingPeriodEnd) {
    }

    private MetricExtractor() {
    }

    public static FinancialHistory extract(List<SourcedFact> facts) {
        // Exercices = dates de clôture des rapports eux-mêmes (on ignore les bilans d'ouverture isolés)
        Map<LocalDate, Map<Metric, FinancialHistory.Value>> byYear = new TreeMap<>(Comparator.reverseOrder());
        for (SourcedFact f : facts) {
            byYear.computeIfAbsent(f.filingPeriodEnd(), _ -> new EnumMap<>(Metric.class));
        }
        // + les années comparatives présentes dans les rapports (ex. 2024 dans le rapport 2025)
        for (SourcedFact f : facts) {
            if (!f.fact().isInstant() && isFullYear(f.fact())) {
                byYear.computeIfAbsent(f.fact().periodEnd(), _ -> new EnumMap<>(Metric.class));
            }
        }

        for (var entry : byYear.entrySet()) {
            LocalDate yearEnd = entry.getKey();
            for (Metric metric : Metric.values()) {
                find(facts, metric, yearEnd).ifPresent(v -> entry.getValue().put(metric, v));
            }
        }

        List<FinancialHistory.Year> years = byYear.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> new FinancialHistory.Year(e.getKey(), Map.copyOf(e.getValue())))
                .toList();
        return new FinancialHistory(years);
    }

    static Optional<FinancialHistory.Value> find(List<SourcedFact> facts, Metric metric, LocalDate yearEnd) {
        for (String concept : metric.getConcepts()) {
            Optional<SourcedFact> best = facts.stream()
                    .filter(f -> matchesConcept(f.fact().concept(), concept))
                    .filter(f -> f.fact().periodEnd().equals(yearEnd))
                    .filter(f -> matchesKind(f.fact(), metric.getKind()))
                    .max(Comparator.comparing(SourcedFact::filingPeriodEnd));
            if (best.isPresent()) {
                SourcedFact f = best.get();
                return Optional.of(new FinancialHistory.Value(
                        f.fact().value(), extractCurrency(f.fact().unit()), f.fact().concept(), f.filing()));
            }
        }
        return Optional.empty();
    }

    /** {@code *:NomLocal} : ce nom local quel que soit le préfixe (extension propre à la société). */
    static boolean matchesConcept(String concept, String candidate) {
        if (candidate.startsWith("*:")) {
            int colon = concept.indexOf(':');
            return colon >= 0 && concept.substring(colon + 1).equals(candidate.substring(2));
        }
        return concept.equals(candidate);
    }

    private static boolean matchesKind(ReportedFact fact, Metric.Kind kind) {
        boolean money = fact.unit().startsWith("iso4217:");
        boolean perShare = fact.unit().endsWith(PER_SHARE_SUFFIX);
        return switch (kind) {
            case STOCK -> fact.isInstant() && money && !perShare;
            case FLOW -> !fact.isInstant() && isFullYear(fact) && money && !perShare;
            case PER_SHARE -> !fact.isInstant() && isFullYear(fact) && money && perShare;
        };
    }

    private static boolean isFullYear(ReportedFact fact) {
        long days = ChronoUnit.DAYS.between(fact.periodStart(), fact.periodEnd()) + 1;
        return days >= MIN_YEAR_DAYS && days <= MAX_YEAR_DAYS;
    }

    /** {@code iso4217:EUR} → {@code EUR} ; {@code iso4217:EUR/xbrli:shares} → {@code EUR}. */
    static String extractCurrency(String unit) {
        String u = unit.startsWith("iso4217:") ? unit.substring("iso4217:".length()) : unit;
        int slash = u.indexOf('/');
        return slash < 0 ? u : u.substring(0, slash);
    }
}
