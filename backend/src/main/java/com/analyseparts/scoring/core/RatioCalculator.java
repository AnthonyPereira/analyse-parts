package com.analyseparts.scoring.core;

import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialHistory.Year;
import com.analyseparts.financials.Metric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.analyseparts.financials.Metric.CAPEX;
import static com.analyseparts.financials.Metric.CASH;
import static com.analyseparts.financials.Metric.CURRENT_ASSETS;
import static com.analyseparts.financials.Metric.CURRENT_LIABILITIES;
import static com.analyseparts.financials.Metric.DIVIDENDS_PAID;
import static com.analyseparts.financials.Metric.EPS_BASIC;
import static com.analyseparts.financials.Metric.EPS_DILUTED;
import static com.analyseparts.financials.Metric.EQUITY;
import static com.analyseparts.financials.Metric.EQUITY_GROUP;
import static com.analyseparts.financials.Metric.LONG_TERM_DEBT;
import static com.analyseparts.financials.Metric.NET_INCOME;
import static com.analyseparts.financials.Metric.NET_INCOME_GROUP;
import static com.analyseparts.financials.Metric.OPERATING_CASH_FLOW;
import static com.analyseparts.financials.Metric.OPERATING_INCOME;
import static com.analyseparts.financials.Metric.REVENUE;
import static com.analyseparts.financials.Metric.SHORT_TERM_DEBT;
import static com.analyseparts.financials.Metric.TOTAL_ASSETS;

/**
 * Calcule les ratios d'une société à partir de son historique d'indicateurs.
 * <p>
 * Java pur : aucune dépendance à Spring ou à la base. Les ratios de l'exercice portent sur le
 * <b>dernier exercice publié</b> ; les ratios de croissance et de régularité utilisent tout
 * l'historique disponible.
 * <p>
 * Un ratio impossible à calculer n'est jamais remplacé par une valeur inventée : il est déclaré
 * indisponible, avec la raison, et le score se calcule sans lui.
 */
public final class RatioCalculator {

    /** Pour une croissance annuelle moyenne, il faut au moins 3 exercices (2 ans d'écart). */
    static final int MIN_YEARS_FOR_TREND = 3;
    private static final double DAYS_PER_YEAR = 365.25;

    /** Un chiffre utilisé dans un calcul, avec sa provenance. */
    public record Input(Metric metric, LocalDate fiscalYearEnd, BigDecimal value, String currency, String concept) {
    }

    public record RatioValue(Ratio ratio, double value, List<Input> inputs) {
    }

    /** Les ratios calculés, et pour les autres la raison de leur absence. */
    public record RatioSet(LocalDate fiscalYearEnd, Map<Ratio, RatioValue> values, Map<Ratio, String> unavailable) {

        public Optional<RatioValue> get(Ratio ratio) {
            return Optional.ofNullable(values.get(ratio));
        }
    }

    /** Résultat d'un calcul : une valeur, ou la raison pour laquelle on n'en a pas. */
    private sealed interface Outcome {
        record Ok(double value, List<Input> inputs) implements Outcome {
        }

        record Missing(String reason) implements Outcome {
        }
    }

    private RatioCalculator() {
    }

    public static RatioSet compute(FinancialHistory history) {
        Map<Ratio, RatioValue> values = new EnumMap<>(Ratio.class);
        Map<Ratio, String> unavailable = new EnumMap<>(Ratio.class);
        if (history.years().isEmpty()) {
            for (Ratio ratio : Ratio.values()) {
                unavailable.put(ratio, "Aucun rapport annuel disponible");
            }
            return new RatioSet(null, values, unavailable);
        }

        Calculation c = new Calculation(history.years());
        for (Ratio ratio : Ratio.values()) {
            switch (c.compute(ratio)) {
                case Outcome.Ok ok -> values.put(ratio, new RatioValue(ratio, ok.value(), ok.inputs()));
                case Outcome.Missing missing -> unavailable.put(ratio, missing.reason());
            }
        }
        return new RatioSet(c.latest.fiscalYearEnd(), values, unavailable);
    }

    /** Le calcul pour une société donnée. */
    private static final class Calculation {

        private final List<Year> years;
        private final Year latest;
        /** Exercice précédent, s'il précède bien le dernier d'environ un an. */
        private final Year previous;

        Calculation(List<Year> years) {
            this.years = years;
            this.latest = years.getFirst();
            this.previous = years.size() > 1 && isOneYearBefore(years.get(1), latest) ? years.get(1) : null;
        }

        Outcome compute(Ratio ratio) {
            return switch (ratio) {
                // Résultat net part du groupe ÷ chiffre d'affaires
                case NET_MARGIN -> divide(first(latest, NET_INCOME_GROUP, NET_INCOME), first(latest, REVENUE),
                        "le résultat net", "le chiffre d'affaires");
                // Résultat opérationnel ÷ chiffre d'affaires
                case OPERATING_MARGIN -> divide(first(latest, OPERATING_INCOME), first(latest, REVENUE),
                        "le résultat opérationnel", "le chiffre d'affaires");
                // Résultat net part du groupe ÷ capitaux propres part du groupe moyens
                case ROE -> roe();
                // Résultat net ÷ total de l'actif moyen
                case ROA -> divideByAverage(first(latest, NET_INCOME), TOTAL_ASSETS,
                        "le résultat net", "le total du bilan");
                case NET_DEBT_TO_EQUITY -> balanceSheetRatio(this::netDebtToEquity);
                // Capitaux propres ÷ total du bilan
                case EQUITY_RATIO -> balanceSheetRatio(() -> divide(first(latest, EQUITY), first(latest, TOTAL_ASSETS),
                        "les capitaux propres", "le total du bilan"));
                // Actifs courants ÷ passifs courants
                case CURRENT_RATIO -> balanceSheetRatio(() -> divide(first(latest, CURRENT_ASSETS),
                        first(latest, CURRENT_LIABILITIES), "les actifs courants", "les passifs courants"));
                // Flux de trésorerie d'exploitation ÷ résultat net
                case CASH_CONVERSION -> divide(first(latest, OPERATING_CASH_FLOW), first(latest, NET_INCOME),
                        "le flux de trésorerie d'exploitation", "le résultat net");
                case FCF_MARGIN -> fcfMargin();
                case REVENUE_GROWTH -> growth(REVENUE, "le chiffre d'affaires");
                case EPS_GROWTH -> epsGrowth();
                case PROFITABLE_YEARS -> profitableYears();
                case PAYOUT_RATIO -> payoutRatio();
                case DIVIDEND_GROWTH -> growth(DIVIDENDS_PAID, "les dividendes versés");
            };
        }

        // --- Ratios qui demandent un peu plus qu'une division ---

        private Outcome roe() {
            // On compare le bénéfice et les capitaux propres de la même "population" d'actionnaires :
            // part du groupe avec part du groupe, ou total avec total.
            Optional<Input> groupIncome = first(latest, NET_INCOME_GROUP);
            if (groupIncome.isPresent() && first(latest, EQUITY_GROUP).isPresent()) {
                return divideByAverage(groupIncome, EQUITY_GROUP, "le résultat net part du groupe",
                        "les capitaux propres part du groupe");
            }
            return divideByAverage(first(latest, NET_INCOME), EQUITY, "le résultat net", "les capitaux propres");
        }

        /** (Dette long terme + dette court terme − trésorerie) ÷ capitaux propres. */
        private Outcome netDebtToEquity() {
            Optional<Input> longTerm = first(latest, LONG_TERM_DEBT);
            Optional<Input> shortTerm = first(latest, SHORT_TERM_DEBT);
            if (longTerm.isEmpty() && shortTerm.isEmpty()) {
                return new Outcome.Missing("Dettes financières non publiées sous un concept standard");
            }
            Optional<Input> cash = first(latest, CASH);
            Optional<Input> equity = first(latest, EQUITY);
            if (cash.isEmpty() || equity.isEmpty()) {
                return new Outcome.Missing("Trésorerie ou capitaux propres manquants");
            }
            List<Input> inputs = new ArrayList<>();
            longTerm.ifPresent(inputs::add);
            shortTerm.ifPresent(inputs::add);
            inputs.add(cash.get());
            inputs.add(equity.get());
            if (!sameCurrency(inputs)) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (signum(equity.get()) <= 0) {
                return new Outcome.Missing("Capitaux propres négatifs ou nuls : ratio non interprétable");
            }
            double netDebt = value(longTerm) + value(shortTerm) - cash.get().value().doubleValue();
            return new Outcome.Ok(netDebt / equity.get().value().doubleValue(), inputs);
        }

        /** (Flux de trésorerie d'exploitation − investissements) ÷ chiffre d'affaires. */
        private Outcome fcfMargin() {
            Optional<Input> ocf = first(latest, OPERATING_CASH_FLOW);
            Optional<Input> capex = first(latest, CAPEX);
            Optional<Input> revenue = first(latest, REVENUE);
            if (ocf.isEmpty() || capex.isEmpty() || revenue.isEmpty()) {
                return new Outcome.Missing(missingLabel(ocf, "le flux de trésorerie d'exploitation",
                        capex, "les investissements", revenue, "le chiffre d'affaires"));
            }
            List<Input> inputs = List.of(ocf.get(), capex.get(), revenue.get());
            if (!sameCurrency(inputs)) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (signum(revenue.get()) <= 0) {
                return new Outcome.Missing("Chiffre d'affaires nul ou négatif");
            }
            // Les investissements sont publiés en positif (décaissement) ; abs() par précaution.
            double fcf = ocf.get().value().doubleValue() - Math.abs(capex.get().value().doubleValue());
            return new Outcome.Ok(fcf / revenue.get().value().doubleValue(), inputs);
        }

        /** Dividendes versés ÷ résultat net part du groupe. */
        private Outcome payoutRatio() {
            Optional<Input> dividends = first(latest, DIVIDENDS_PAID);
            Optional<Input> income = first(latest, NET_INCOME_GROUP, NET_INCOME);
            if (dividends.isEmpty()) {
                return new Outcome.Missing("Dividendes versés non publiés sous un concept standard");
            }
            if (income.isEmpty()) {
                return new Outcome.Missing("Résultat net manquant");
            }
            if (!sameCurrency(List.of(dividends.get(), income.get()))) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (signum(income.get()) <= 0) {
                return new Outcome.Missing("Perte sur l'exercice : le dividende est payé sur les réserves");
            }
            return new Outcome.Ok(Math.abs(dividends.get().value().doubleValue())
                    / income.get().value().doubleValue(), List.of(dividends.get(), income.get()));
        }

        /** Bénéfice par action : on prend le BPA de base, ou le dilué si le premier manque aux deux bouts. */
        private Outcome epsGrowth() {
            Outcome basic = growth(EPS_BASIC, "le bénéfice par action");
            if (basic instanceof Outcome.Ok) {
                return basic;
            }
            Outcome diluted = growth(EPS_DILUTED, "le bénéfice par action");
            return diluted instanceof Outcome.Ok ? diluted : basic;
        }

        /**
         * Croissance annuelle moyenne (en anglais CAGR) entre le plus ancien et le plus récent
         * exercice : {@code (valeur finale ÷ valeur initiale)^(1 / nombre d'années) − 1}.
         * Exemple : 100 → 121 en 2 ans = +10 % par an (et non +10,5 % : 1,1 × 1,1 = 1,21).
         */
        private Outcome growth(Metric metric, String what) {
            List<Input> series = years.stream().map(y -> first(y, metric)).flatMap(Optional::stream).toList();
            if (series.size() < MIN_YEARS_FOR_TREND) {
                return new Outcome.Missing("Il faut au moins " + MIN_YEARS_FOR_TREND + " exercices pour "
                        + what + " (disponibles : " + series.size() + ")");
            }
            Input newest = series.getFirst();
            Input oldest = series.getLast();
            if (!sameCurrency(List.of(newest, oldest))) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (signum(newest) <= 0 || signum(oldest) <= 0) {
                return new Outcome.Missing("Valeur négative ou nulle au début ou à la fin : croissance non calculable");
            }
            double span = ChronoUnit.DAYS.between(oldest.fiscalYearEnd(), newest.fiscalYearEnd()) / DAYS_PER_YEAR;
            double cagr = Math.pow(newest.value().doubleValue() / oldest.value().doubleValue(), 1 / span) - 1;
            return new Outcome.Ok(cagr, List.of(newest, oldest));
        }

        /** Part des exercices clos sur un bénéfice. */
        private Outcome profitableYears() {
            List<Input> incomes = years.stream().map(y -> first(y, NET_INCOME_GROUP, NET_INCOME))
                    .flatMap(Optional::stream).toList();
            if (incomes.size() < MIN_YEARS_FOR_TREND) {
                return new Outcome.Missing("Il faut au moins " + MIN_YEARS_FOR_TREND
                        + " exercices (disponibles : " + incomes.size() + ")");
            }
            long profitable = incomes.stream().filter(i -> signum(i) > 0).count();
            return new Outcome.Ok((double) profitable / incomes.size(), incomes);
        }

        /**
         * Les banques et assurances présentent leur bilan par ordre de liquidité (norme IAS 1),
         * sans séparer le court et le long terme. Leur dette est leur matière première (dépôts des
         * clients) : les ratios d'endettement d'une entreprise industrielle n'ont pas de sens pour elles.
         */
        private Outcome balanceSheetRatio(java.util.function.Supplier<Outcome> ratio) {
            if (first(latest, CURRENT_ASSETS).isEmpty() && first(latest, CURRENT_LIABILITIES).isEmpty()) {
                return new Outcome.Missing("Bilan présenté par ordre de liquidité (banque, assurance…) : "
                        + "ratio non pertinent");
            }
            return ratio.get();
        }

        // --- Briques de calcul ---

        private Outcome divide(Optional<Input> numerator, Optional<Input> denominator, String numLabel, String denLabel) {
            if (numerator.isEmpty() || denominator.isEmpty()) {
                return new Outcome.Missing(missingLabel(numerator, numLabel, denominator, denLabel));
            }
            List<Input> inputs = List.of(numerator.get(), denominator.get());
            if (!sameCurrency(inputs)) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (signum(denominator.get()) <= 0) {
                return new Outcome.Missing(capitalize(denLabel) + " : valeur négative ou nulle, ratio non interprétable");
            }
            return new Outcome.Ok(numerator.get().value().doubleValue() / denominator.get().value().doubleValue(), inputs);
        }

        /**
         * Comme {@link #divide}, mais avec la moyenne du dénominateur entre le début et la fin de
         * l'exercice : le bénéfice est gagné sur toute l'année, avec des capitaux qui ont évolué.
         * Sans exercice précédent, on prend la valeur de fin d'exercice.
         */
        private Outcome divideByAverage(Optional<Input> numerator, Metric denominator, String numLabel, String denLabel) {
            Optional<Input> end = first(latest, denominator);
            Optional<Input> start = previous == null ? Optional.empty() : first(previous, denominator);
            if (numerator.isEmpty() || end.isEmpty()) {
                return new Outcome.Missing(missingLabel(numerator, numLabel, end, denLabel));
            }
            List<Input> inputs = new ArrayList<>(List.of(numerator.get(), end.get()));
            start.ifPresent(inputs::add);
            if (!sameCurrency(inputs)) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            double average = start.map(s -> (s.value().doubleValue() + end.get().value().doubleValue()) / 2)
                    .orElse(end.get().value().doubleValue());
            if (signum(end.get()) <= 0 || average <= 0) {
                return new Outcome.Missing(capitalize(denLabel) + " : valeur négative ou nulle, ratio non interprétable");
            }
            return new Outcome.Ok(numerator.get().value().doubleValue() / average, inputs);
        }

        /** Le premier indicateur disponible parmi les candidats, pour un exercice. */
        private static Optional<Input> first(Year year, Metric... candidates) {
            for (Metric metric : candidates) {
                Optional<FinancialHistory.Value> v = year.get(metric);
                if (v.isPresent()) {
                    return Optional.of(new Input(metric, year.fiscalYearEnd(), v.get().value(),
                            v.get().currency(), v.get().concept()));
                }
            }
            return Optional.empty();
        }

        private static boolean isOneYearBefore(Year candidate, Year year) {
            long days = ChronoUnit.DAYS.between(candidate.fiscalYearEnd(), year.fiscalYearEnd());
            return days >= 330 && days <= 400;
        }

        private static boolean sameCurrency(List<Input> inputs) {
            return inputs.stream().map(Input::currency).distinct().count() <= 1;
        }

        private static int signum(Input input) {
            return input.value().signum();
        }

        private static double value(Optional<Input> input) {
            return input.map(i -> i.value().doubleValue()).orElse(0.0);
        }

        /** Paires (Optional, libellé) : liste les libellés dont l'Optional est vide. */
        private static String missingLabel(Object... optionalAndLabelPairs) {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < optionalAndLabelPairs.length; i += 2) {
                if (((Optional<?>) optionalAndLabelPairs[i]).isEmpty()) {
                    missing.add((String) optionalAndLabelPairs[i + 1]);
                }
            }
            return "Donnée manquante : " + String.join(", ", missing);
        }

        private static String capitalize(String s) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
