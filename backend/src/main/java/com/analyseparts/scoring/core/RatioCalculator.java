package com.analyseparts.scoring.core;

import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialHistory.Year;
import com.analyseparts.financials.Metric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.analyseparts.financials.Metric.CAPEX;
import static com.analyseparts.financials.Metric.COST_OF_RISK;
import static com.analyseparts.financials.Metric.DIVIDENDS_PER_SHARE;
import static com.analyseparts.financials.Metric.GROSS_OPERATING_INCOME;
import static com.analyseparts.financials.Metric.NET_BANKING_INCOME;
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
 * Calcule les ratios d'une société à partir de son historique d'indicateurs et, pour les
 * ratios de valorisation, de son cours de bourse.
 * <p>
 * Java pur : aucune dépendance à Spring ou à la base. Les ratios de l'exercice portent sur le
 * <b>dernier exercice publié</b> ; les ratios de croissance et de régularité utilisent tout
 * l'historique disponible ; les ratios de valorisation comparent le <b>cours actuel</b> aux
 * chiffres du dernier exercice.
 * <p>
 * Un ratio impossible à calculer n'est jamais remplacé par une valeur inventée : il est déclaré
 * indisponible, avec la raison, et le score se calcule sans lui.
 */
public final class RatioCalculator {

    /** Pour une croissance annuelle moyenne, il faut au moins 3 exercices (2 ans d'écart). */
    static final int MIN_YEARS_FOR_TREND = 3;
    /** Un dividende plus de 2 fois supérieur à la médiane des exercices précédents est exceptionnel. */
    static final double EXCEPTIONAL_FACTOR = 2.0;
    static final int EXCEPTIONAL_LOOKBACK = 3;
    /** En dessous, le BPA publié (arrondi au centime) ne permet pas de retrouver le nombre d'actions. */
    static final BigDecimal MIN_EPS_FOR_SHARE_COUNT = new BigDecimal("0.25");
    /**
     * Un cours inférieur au BPA (PER sous 1) voudrait dire que la société gagne en un an plus que
     * sa valeur en bourse : c'est presque toujours un BPA publié dans une autre unité (en pence
     * au lieu de livres, en milliers…). Ce BPA n'est alors utilisé ni pour le PER ni pour
     * retrouver le nombre d'actions.
     */
    static final double MIN_PLAUSIBLE_PER = 1.0;
    /**
     * Un chiffre d'affaires multiplié ou divisé par 500 ou plus en un an, alors que le résultat net
     * varie de moins d'un facteur 10, trahit une erreur d'unité dans le rapport (milliers saisis
     * comme des euros, par exemple) : les ratios qui l'utilisent sont écartés.
     */
    static final double REVENUE_SCALE_BREAK = 500;
    static final double INCOME_STABLE_FACTOR = 10;
    /** Concept de dernier recours pour le chiffre d'affaires : les ventes de biens seules. */
    static final String PARTIAL_REVENUE_CONCEPT = "ifrs-full:RevenueFromSaleOfGoods";
    private static final double DAYS_PER_YEAR = 365.25;

    /**
     * Un chiffre utilisé dans un calcul, avec sa provenance.
     *
     * @param item     indicateur ({@link Metric}) ou donnée de marché (PRICE, SHARES, MARKET_CAP)
     * @param date     date de clôture de l'exercice, ou date du cours
     * @param currency devise, ou null pour un nombre d'actions
     * @param source   concept XBRL, ou source de la donnée de marché
     */
    public record Input(String item, LocalDate date, BigDecimal value, String currency, String source) {
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

    /** Sans données de marché : les ratios de valorisation sont indisponibles. */
    public static RatioSet compute(FinancialHistory history) {
        return compute(history, null, "Cours de bourse non fourni");
    }

    /**
     * @param valuation         cours et nombre d'actions, ou null
     * @param noValuationReason raison affichée si {@code valuation} est null
     */
    public static RatioSet compute(FinancialHistory history, Valuation valuation, String noValuationReason) {
        Map<Ratio, RatioValue> values = new EnumMap<>(Ratio.class);
        Map<Ratio, String> unavailable = new EnumMap<>(Ratio.class);
        if (history.years().isEmpty()) {
            for (Ratio ratio : Ratio.values()) {
                unavailable.put(ratio, "Aucun rapport annuel disponible");
            }
            return new RatioSet(null, values, unavailable);
        }

        Calculation c = new Calculation(history.years(), valuation, noValuationReason);
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
        private final Valuation valuation;
        private final String noValuationReason;

        Calculation(List<Year> years, Valuation valuation, String noValuationReason) {
            this.years = years;
            this.latest = years.getFirst();
            this.previous = years.size() > 1 && isOneYearBefore(years.get(1), latest) ? years.get(1) : null;
            this.valuation = valuation;
            this.noValuationReason = noValuationReason;
        }

        Outcome compute(Ratio ratio) {
            return switch (ratio) {
                // Résultat net part du groupe ÷ chiffre d'affaires (produit net bancaire pour une banque)
                case NET_MARGIN -> checkRevenueScale(() -> divide(findFirst(latest, NET_INCOME_GROUP, NET_INCOME), findRevenue(latest),
                        "le résultat net", "le chiffre d'affaires"));
                // Résultat opérationnel ÷ chiffre d'affaires
                case OPERATING_MARGIN -> checkRevenueScale(() -> divide(findFirst(latest, OPERATING_INCOME), findRevenue(latest),
                        "le résultat opérationnel", "le chiffre d'affaires"));
                case COST_INCOME_RATIO -> computeCostIncome();
                case COST_OF_RISK_RATIO -> computeCostOfRisk();
                // Résultat net part du groupe ÷ capitaux propres part du groupe moyens
                case ROE -> computeRoe();
                // Résultat net ÷ total de l'actif moyen
                case ROA -> divideByAverage(findFirst(latest, NET_INCOME), TOTAL_ASSETS,
                        "le résultat net", "le total du bilan");
                case NET_DEBT_TO_EQUITY -> computeBalanceSheetRatio(this::computeNetDebtToEquity);
                // Capitaux propres ÷ total du bilan
                case EQUITY_RATIO -> computeBalanceSheetRatio(() -> divide(findFirst(latest, EQUITY), findFirst(latest, TOTAL_ASSETS),
                        "les capitaux propres", "le total du bilan"));
                // Actifs courants ÷ passifs courants
                case CURRENT_RATIO -> computeBalanceSheetRatio(() -> divide(findFirst(latest, CURRENT_ASSETS),
                        findFirst(latest, CURRENT_LIABILITIES), "les actifs courants", "les passifs courants"));
                // Flux de trésorerie d'exploitation ÷ résultat net
                case CASH_CONVERSION -> divide(findFirst(latest, OPERATING_CASH_FLOW), findFirst(latest, NET_INCOME),
                        "le flux de trésorerie d'exploitation", "le résultat net");
                case FCF_MARGIN -> checkRevenueScale(() -> computeFreeCashFlow().map(fcf -> divide(Optional.of(fcf),
                        findRevenue(latest), "le cash-flow libre", "le chiffre d'affaires"), Outcome.Missing::new));
                case REVENUE_GROWTH -> checkRevenueScale(() -> computeGrowth(Calculation::findRevenue, "le chiffre d'affaires"));
                case EPS_GROWTH -> computeEpsGrowth();
                case PROFITABLE_YEARS -> countProfitableYears();
                case PAYOUT_RATIO -> computePayoutRatio();
                case DIVIDEND_GROWTH -> computeGrowth(y -> y == latest ? computeRecurringDividends() : findDividendsPaid(y), "les dividendes versés");
                // Dividendes versés ÷ capitalisation boursière
                case DIVIDEND_YIELD -> computeWithMarketCap(cap -> computeRecurringDividends()
                        .map(d -> divide(Optional.of(d), Optional.of(cap), "les dividendes versés", "la capitalisation"))
                        .orElse(new Outcome.Missing("Dividendes versés non publiés sous un concept standard")));
                case PER -> computePer();
                case EV_TO_EBIT -> computeBalanceSheetRatio(this::computeEvToEbit);
                // Cash-flow libre ÷ capitalisation boursière
                case FCF_YIELD -> computeWithMarketCap(cap -> computeFreeCashFlow().map(fcf -> divide(Optional.of(fcf),
                        Optional.of(cap), "le cash-flow libre", "la capitalisation"), Outcome.Missing::new));
                // Capitalisation ÷ capitaux propres part du groupe
                case PRICE_TO_BOOK -> computeWithMarketCap(cap -> {
                    Outcome pb = divide(Optional.of(cap), findFirst(latest, EQUITY_GROUP, EQUITY),
                            "la capitalisation", "les capitaux propres");
                    return pb;
                });
            };
        }

        // --- Ratios qui demandent un peu plus qu'une division ---

        private Outcome computeRoe() {
            // On compare le bénéfice et les capitaux propres de la même "population" d'actionnaires :
            // part du groupe avec part du groupe, ou total avec total.
            Optional<Input> groupIncome = findFirst(latest, NET_INCOME_GROUP);
            if (groupIncome.isPresent() && findFirst(latest, EQUITY_GROUP).isPresent()) {
                return divideByAverage(groupIncome, EQUITY_GROUP, "le résultat net part du groupe",
                        "les capitaux propres part du groupe");
            }
            return divideByAverage(findFirst(latest, NET_INCOME), EQUITY, "le résultat net", "les capitaux propres");
        }

        /** (Dette long terme + dette court terme − trésorerie) ÷ capitaux propres. */
        private Outcome computeNetDebtToEquity() {
            Optional<Input> equity = findFirst(latest, EQUITY);
            if (equity.isEmpty()) {
                return new Outcome.Missing("Capitaux propres manquants");
            }
            return computeNetDebt().map(nd -> {
                if (!haveSameCurrency(List.of(nd, equity.get()))) {
                    return (Outcome) new Outcome.Missing("Chiffres dans des devises différentes");
                }
                if (computeSign(equity.get()) <= 0) {
                    return new Outcome.Missing("Capitaux propres négatifs ou nuls : ratio non interprétable");
                }
                List<Input> inputs = new ArrayList<>(findNetDebtInputs());
                inputs.add(equity.get());
                return new Outcome.Ok(nd.value().doubleValue() / equity.get().value().doubleValue(), inputs);
            }, Outcome.Missing::new);
        }

        /**
         * Dette nette = dettes financières − trésorerie, renvoyée comme une {@link Input} calculée.
         * Si seule une des deux dettes (long ou court terme) est publiée, l'autre compte pour zéro.
         */
        private Either<Input> computeNetDebt() {
            Optional<Input> longTerm = findFirst(latest, LONG_TERM_DEBT);
            Optional<Input> shortTerm = findFirst(latest, SHORT_TERM_DEBT);
            if (longTerm.isEmpty() && shortTerm.isEmpty()) {
                return Either.createMissing("Dettes financières non publiées sous un concept standard");
            }
            Optional<Input> cash = findFirst(latest, CASH);
            if (cash.isEmpty()) {
                return Either.createMissing("Trésorerie manquante");
            }
            List<Input> parts = findNetDebtInputs();
            if (!haveSameCurrency(parts)) {
                return Either.createMissing("Chiffres dans des devises différentes");
            }
            double value = extractValue(longTerm) + extractValue(shortTerm) - cash.get().value().doubleValue();
            return Either.of(new Input("NET_DEBT", latest.fiscalYearEnd(), BigDecimal.valueOf(value),
                    cash.get().currency(), "dettes financières − trésorerie"));
        }

        private List<Input> findNetDebtInputs() {
            List<Input> inputs = new ArrayList<>();
            findFirst(latest, LONG_TERM_DEBT).ifPresent(inputs::add);
            findFirst(latest, SHORT_TERM_DEBT).ifPresent(inputs::add);
            findFirst(latest, CASH).ifPresent(inputs::add);
            return inputs;
        }

        /** Cash-flow libre = flux de trésorerie d'exploitation − investissements. */
        private Either<Input> computeFreeCashFlow() {
            Optional<Input> ocf = findFirst(latest, OPERATING_CASH_FLOW);
            Optional<Input> capex = findFirst(latest, CAPEX);
            if (ocf.isEmpty() || capex.isEmpty()) {
                return Either.createMissing(buildMissingLabel(ocf, "le flux de trésorerie d'exploitation", capex, "les investissements"));
            }
            if (!haveSameCurrency(List.of(ocf.get(), capex.get()))) {
                return Either.createMissing("Chiffres dans des devises différentes");
            }
            // Les investissements sont publiés en positif (décaissement) ; toAbsolute() par précaution.
            double fcf = ocf.get().value().doubleValue() - Math.abs(capex.get().value().doubleValue());
            return Either.of(new Input("FREE_CASH_FLOW", latest.fiscalYearEnd(), BigDecimal.valueOf(fcf),
                    ocf.get().currency(), "flux d'exploitation (" + ocf.get().value() + ") − investissements ("
                    + capex.get().value().abs() + ")"));
        }

        // --- Banques ---

        /**
         * Coefficient d'exploitation = frais de fonctionnement ÷ produit net bancaire. Les banques
         * publient le résultat brut d'exploitation (PNB − frais) : coefficient = 1 − RBE ÷ PNB.
         */
        private Outcome computeCostIncome() {
            Optional<Input> nbi = findFirst(latest, NET_BANKING_INCOME);
            if (nbi.isEmpty()) {
                return new Outcome.Missing("Ratio propre aux banques : produit net bancaire non publié");
            }
            Outcome share = divide(findFirst(latest, GROSS_OPERATING_INCOME), nbi,
                    "le résultat brut d'exploitation", "le produit net bancaire");
            return share instanceof Outcome.Ok ok ? new Outcome.Ok(1 - ok.value(), ok.inputs()) : share;
        }

        /**
         * Coût du risque ÷ produit net bancaire. Le coût du risque est publié par certaines banques ;
         * sinon il se déduit du compte de résultat : résultat brut d'exploitation − résultat
         * d'exploitation (c'est la seule ligne qui les sépare).
         */
        private Outcome computeCostOfRisk() {
            Optional<Input> nbi = findFirst(latest, NET_BANKING_INCOME);
            if (nbi.isEmpty()) {
                return new Outcome.Missing("Ratio propre aux banques : produit net bancaire non publié");
            }
            Optional<Input> cost = findFirst(latest, COST_OF_RISK).map(Calculation::toAbsolute);
            if (cost.isEmpty()) {
                Optional<Input> gross = findFirst(latest, GROSS_OPERATING_INCOME);
                Optional<Input> operating = findFirst(latest, OPERATING_INCOME);
                if (gross.isPresent() && operating.isPresent() && haveSameCurrency(List.of(gross.get(), operating.get()))) {
                    BigDecimal derived = gross.get().value().subtract(operating.get().value());
                    cost = Optional.of(new Input("COST_OF_RISK", latest.fiscalYearEnd(), derived, gross.get().currency(),
                            "résultat brut d'exploitation (" + gross.get().value() + ") − résultat d'exploitation ("
                                    + operating.get().value() + ")"));
                }
            }
            return divide(cost, nbi, "le coût du risque", "le produit net bancaire");
        }

        /**
         * Écarte un ratio fondé sur le chiffre d'affaires quand celui-ci change d'échelle sans raison,
         * ou quand seules les ventes de biens sont publiées et qu'elles ne sont qu'une petite partie
         * de l'activité (résultat d'exploitation plus grand qu'elles : le vrai total est publié sous
         * un concept propre à la société).
         */
        private Outcome checkRevenueScale(Supplier<Outcome> ratio) {
            if (hasRevenueScaleBreak()) {
                return new Outcome.Missing("Chiffre d'affaires incohérent avec les exercices précédents "
                        + "(erreur d'unité probable dans le rapport)");
            }
            if (isPartialRevenue()) {
                return new Outcome.Missing("Seules les ventes de biens sont publiées sous un concept standard, "
                        + "et elles sont inférieures au résultat d'exploitation : chiffre d'affaires total inconnu");
            }
            return ratio.get();
        }

        private boolean isPartialRevenue() {
            Optional<Input> revenue = findRevenue(latest);
            Optional<Input> operating = findFirst(latest, OPERATING_INCOME);
            return revenue.isPresent() && operating.isPresent()
                    && PARTIAL_REVENUE_CONCEPT.equals(revenue.get().source())
                    && operating.get().value().abs().compareTo(revenue.get().value().abs()) > 0;
        }

        /**
         * Le dernier exercice comparé aux trois précédents : un rapport erroné réécrit souvent aussi
         * l'exercice d'avant (chiffres comparatifs), la rupture n'apparaît alors qu'avec l'avant-dernier.
         */
        private boolean hasRevenueScaleBreak() {
            Optional<Input> revenue = findRevenue(latest);
            Optional<Input> income = findFirst(latest, NET_INCOME_GROUP, NET_INCOME);
            if (revenue.isEmpty() || income.isEmpty() || computeSign(revenue.get()) <= 0) {
                return false;
            }
            return years.stream().skip(1).limit(EXCEPTIONAL_LOOKBACK).anyMatch(earlier -> {
                Optional<Input> earlierRevenue = findRevenue(earlier);
                Optional<Input> earlierIncome = findFirst(earlier, NET_INCOME_GROUP, NET_INCOME);
                return earlierRevenue.isPresent() && earlierIncome.isPresent()
                        && computeSign(earlierRevenue.get()) > 0
                        && computeSign(income.get()) * computeSign(earlierIncome.get()) > 0
                        && computeFactor(revenue.get(), earlierRevenue.get()) >= REVENUE_SCALE_BREAK
                        && computeFactor(income.get(), earlierIncome.get()) < INCOME_STABLE_FACTOR;
            });
        }

        /** Le rapport entre deux montants de même signe, toujours supérieur ou égal à 1. */
        private static double computeFactor(Input a, Input b) {
            double ratio = Math.abs(a.value().doubleValue() / b.value().doubleValue());
            return Math.max(ratio, 1 / ratio);
        }

        /**
         * Le BPA est-il compatible avec le cours (PER d'au moins 1 en valeur absolue) ? Sans cours
         * dans la même devise, on ne peut pas le vérifier : il est gardé.
         */
        private boolean isPlausibleEps(Input eps) {
            if (eps.value().signum() == 0) {
                return false;
            }
            Input price = findPriceInput();
            return !haveSameCurrency(List.of(price, eps))
                    || Math.abs(price.value().doubleValue() / eps.value().doubleValue()) >= MIN_PLAUSIBLE_PER;
        }

        /** Le chiffre d'affaires, ou pour une banque son équivalent : le produit net bancaire. */
        private static Optional<Input> findRevenue(Year year) {
            return findFirst(year, REVENUE, NET_BANKING_INCOME);
        }

        /**
         * Les dividendes versés sur l'exercice (en positif). À défaut du montant total, dividende
         * par action × nombre d'actions, ce nombre étant retrouvé par résultat net ÷ BPA.
         */
        private static Optional<Input> findDividendsPaid(Year year) {
            Optional<Input> total = findFirst(year, DIVIDENDS_PAID);
            if (total.isPresent()) {
                return total.map(Calculation::toAbsolute);
            }
            Optional<Input> perShare = findFirst(year, DIVIDENDS_PER_SHARE);
            Optional<Input> income = findFirst(year, NET_INCOME_GROUP);
            Optional<Input> eps = findFirst(year, EPS_BASIC);
            if (perShare.isEmpty() || income.isEmpty() || eps.isEmpty() || computeSign(eps.get()) <= 0
                    || computeSign(income.get()) <= 0 || !haveSameCurrency(List.of(perShare.get(), income.get(), eps.get()))) {
                return Optional.empty();
            }
            double shares = income.get().value().doubleValue() / eps.get().value().doubleValue();
            double amount = perShare.get().value().doubleValue() * shares;
            return Optional.of(new Input("DIVIDENDS_PAID", year.fiscalYearEnd(), BigDecimal.valueOf(Math.round(amount)),
                    perShare.get().currency(), "dividende par action (" + perShare.get().value()
                    + ") × nombre d'actions (résultat net ÷ BPA)"));
        }

        /**
         * Les dividendes du dernier exercice, sans la part exceptionnelle. Un versement plus de deux fois
         * supérieur à la médiane des exercices précédents (cession, trésorerie redistribuée) ne se
         * répétera pas : on retient alors cette médiane, le dividende « habituel ».
         */
        private Optional<Input> computeRecurringDividends() {
            Optional<Input> last = findDividendsPaid(latest);
            List<Input> previous = years.stream().skip(1).limit(EXCEPTIONAL_LOOKBACK).map(Calculation::findDividendsPaid)
                    .flatMap(Optional::stream).filter(p -> last.isPresent() && p.currency().equals(last.get().currency()))
                    .toList();
            if (last.isEmpty() || previous.size() < 2) {
                return last;
            }
            double usual = computeMedian(previous.stream().mapToDouble(p -> p.value().doubleValue()).toArray());
            if (last.get().value().doubleValue() <= EXCEPTIONAL_FACTOR * usual) {
                return last;
            }
            return Optional.of(new Input("DIVIDENDS_PAID", latest.fiscalYearEnd(), BigDecimal.valueOf(Math.round(usual)),
                    last.get().currency(), "dividende habituel : médiane des " + previous.size()
                    + " exercices précédents (versement exceptionnel de " + last.get().value() + " écarté)"));
        }

        private static double computeMedian(double[] values) {
            double[] sorted = values.clone();
            java.util.Arrays.sort(sorted);
            int n = sorted.length;
            return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        }

        /** Dividendes versés ÷ résultat net part du groupe. */
        private Outcome computePayoutRatio() {
            Optional<Input> dividends = computeRecurringDividends();
            Optional<Input> income = findFirst(latest, NET_INCOME_GROUP, NET_INCOME);
            if (dividends.isEmpty()) {
                return new Outcome.Missing("Dividendes versés non publiés sous un concept standard");
            }
            if (income.isEmpty()) {
                return new Outcome.Missing("Résultat net manquant");
            }
            if (!haveSameCurrency(List.of(dividends.get(), income.get()))) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (computeSign(income.get()) <= 0) {
                // Un dividende versé malgré une perte n'est pas couvert par le bénéfice : taux négatif (dividende ÷ perte), donc 0 point, plutôt
                // qu'un « indisponible » qui reporterait son poids sur le rendement et récompenserait la société.
                if (computeSign(dividends.get()) > 0) {
                    return new Outcome.Ok(dividends.get().value().doubleValue() / income.get().value().doubleValue(), List.of(dividends.get(), income.get()));
                }
                return new Outcome.Missing("Perte sur l'exercice et aucun dividende versé");
            }
            return new Outcome.Ok(Math.abs(dividends.get().value().doubleValue())
                    / income.get().value().doubleValue(), List.of(dividends.get(), income.get()));
        }

        /** Bénéfice par action : on prend le BPA de base, ou le dilué si le premier manque aux deux bouts. */
        private Outcome computeEpsGrowth() {
            Outcome basic = computeGrowth(EPS_BASIC, "le bénéfice par action");
            if (basic instanceof Outcome.Ok) {
                return basic;
            }
            Outcome diluted = computeGrowth(EPS_DILUTED, "le bénéfice par action");
            return diluted instanceof Outcome.Ok ? diluted : basic;
        }

        /**
         * Croissance annuelle moyenne (en anglais CAGR) entre le plus ancien et le plus récent
         * exercice : {@code (valeur finale ÷ valeur initiale)^(1 / nombre d'années) − 1}.
         * Exemple : 100 → 121 en 2 ans = +10 % par an (et non +10,5 % : 1,1 × 1,1 = 1,21).
         */
        private Outcome computeGrowth(Metric metric, String what) {
            return computeGrowth(y -> findFirst(y, metric), what);
        }

        private Outcome computeGrowth(java.util.function.Function<Year, Optional<Input>> valueOf, String what) {
            List<Input> series = years.stream().map(valueOf).flatMap(Optional::stream).toList();
            if (series.size() < MIN_YEARS_FOR_TREND) {
                return new Outcome.Missing("Il faut au moins " + MIN_YEARS_FOR_TREND + " exercices pour "
                        + what + " (disponibles : " + series.size() + ")");
            }
            Input newest = series.getFirst();
            Input oldest = series.getLast();
            if (!haveSameCurrency(List.of(newest, oldest))) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (computeSign(newest) <= 0 || computeSign(oldest) <= 0) {
                return new Outcome.Missing("Valeur négative ou nulle au début ou à la fin : croissance non calculable");
            }
            double span = ChronoUnit.DAYS.between(oldest.date(), newest.date()) / DAYS_PER_YEAR;
            double cagr = Math.pow(newest.value().doubleValue() / oldest.value().doubleValue(), 1 / span) - 1;
            return new Outcome.Ok(cagr, List.of(newest, oldest));
        }

        /** Part des exercices clos sur un bénéfice. */
        private Outcome countProfitableYears() {
            List<Input> incomes = years.stream().map(y -> findFirst(y, NET_INCOME_GROUP, NET_INCOME))
                    .flatMap(Optional::stream).toList();
            if (incomes.size() < MIN_YEARS_FOR_TREND) {
                return new Outcome.Missing("Il faut au moins " + MIN_YEARS_FOR_TREND
                        + " exercices (disponibles : " + incomes.size() + ")");
            }
            long profitable = incomes.stream().filter(i -> computeSign(i) > 0).count();
            return new Outcome.Ok((double) profitable / incomes.size(), incomes);
        }

        // --- Valorisation ---

        /** Cours ÷ bénéfice par action du dernier exercice. */
        private Outcome computePer() {
            if (valuation == null) {
                return new Outcome.Missing(noValuationReason);
            }
            Optional<Input> eps = findFirst(latest, EPS_BASIC, EPS_DILUTED);
            if (eps.isEmpty()) {
                return new Outcome.Missing("Bénéfice par action manquant");
            }
            Input price = findPriceInput();
            if (!haveSameCurrency(List.of(price, eps.get()))) {
                return new Outcome.Missing("Cours et bénéfice dans des devises différentes");
            }
            if (computeSign(eps.get()) <= 0) {
                return new Outcome.Missing("Perte sur l'exercice : PER non significatif");
            }
            if (!isPlausibleEps(eps.get())) {
                return new Outcome.Missing("Bénéfice par action supérieur au cours : unité probablement erronée dans le rapport");
            }
            return new Outcome.Ok(price.value().doubleValue() / eps.get().value().doubleValue(),
                    List.of(price, eps.get()));
        }

        /** (Capitalisation + dette nette) ÷ résultat opérationnel. */
        private Outcome computeEvToEbit() {
            return computeWithMarketCap(cap -> computeNetDebt().map(nd -> {
                Optional<Input> ebit = findFirst(latest, OPERATING_INCOME);
                if (ebit.isEmpty()) {
                    return (Outcome) new Outcome.Missing("Résultat opérationnel manquant");
                }
                if (!haveSameCurrency(List.of(cap, nd, ebit.get()))) {
                    return new Outcome.Missing("Chiffres dans des devises différentes");
                }
                if (computeSign(ebit.get()) <= 0) {
                    return new Outcome.Missing("Résultat opérationnel négatif ou nul : ratio non significatif");
                }
                double ev = cap.value().doubleValue() + nd.value().doubleValue();
                List<Input> inputs = new ArrayList<>(List.of(cap, nd, ebit.get()));
                return new Outcome.Ok(ev / ebit.get().value().doubleValue(), inputs);
            }, Outcome.Missing::new));
        }

        /**
         * Capitalisation boursière = cours × nombre d'actions. Le nombre d'actions vient de la fiche
         * Euronext ; à défaut, on le retrouve à partir du BPA : par définition,
         * BPA = résultat net part du groupe ÷ nombre moyen d'actions.
         */
        private Outcome computeWithMarketCap(java.util.function.Function<Input, Outcome> ratio) {
            if (valuation == null) {
                return new Outcome.Missing(noValuationReason);
            }
            Input price = findPriceInput();
            Optional<Input> shares = findShares();
            if (shares.isEmpty()) {
                return new Outcome.Missing("Nombre d'actions inconnu");
            }
            BigDecimal cap = price.value().multiply(shares.get().value());
            return ratio.apply(new Input("MARKET_CAP", price.date(), cap, price.currency(),
                    "cours (" + price.value() + ") × actions (" + shares.get().value() + ", "
                            + shares.get().source() + ")"));
        }

        private Optional<Input> findShares() {
            LocalDate priceDate = findPriceInput().date();
            if (valuation.shares() != null && valuation.shares() > 0) {
                return Optional.of(new Input("SHARES", priceDate, BigDecimal.valueOf(valuation.shares()), null,
                        "actions admises (Euronext)"));
            }
            Optional<Input> income = findFirst(latest, NET_INCOME_GROUP);
            Optional<Input> eps = findFirst(latest, EPS_BASIC);
            if (income.isEmpty() || eps.isEmpty() || eps.get().value().abs().compareTo(MIN_EPS_FOR_SHARE_COUNT) < 0
                    || !isPlausibleEps(eps.get())) {
                return Optional.empty();
            }
            BigDecimal count = income.get().value().divide(eps.get().value(), 0, java.math.RoundingMode.HALF_UP);
            return count.signum() > 0
                    ? Optional.of(new Input("SHARES", latest.fiscalYearEnd(), count, null, "résultat net ÷ BPA"))
                    : Optional.empty();
        }

        private Input findPriceInput() {
            String source = valuation.priceSource()
                    + (valuation.quotedCurrency().equals(valuation.currency()) ? ""
                    : ", " + valuation.quotedPrice() + " " + valuation.quotedCurrency() + " converti");
            return new Input("PRICE", LocalDate.ofInstant(valuation.priceAsOf(), ZoneOffset.UTC), valuation.price(),
                    valuation.currency(), source);
        }

        /**
         * Les banques et assurances présentent leur bilan par ordre de liquidité (norme IAS 1),
         * sans séparer le court et le long terme. Leur dette est leur matière première (dépôts des
         * clients) : les ratios d'endettement d'une entreprise industrielle n'ont pas de sens pour elles.
         */
        private Outcome computeBalanceSheetRatio(Supplier<Outcome> ratio) {
            if (findFirst(latest, CURRENT_ASSETS).isEmpty() && findFirst(latest, CURRENT_LIABILITIES).isEmpty()) {
                return new Outcome.Missing("Bilan présenté par ordre de liquidité (banque, assurance…) : "
                        + "ratio non pertinent");
            }
            return ratio.get();
        }

        // --- Briques de calcul ---

        private Outcome divide(Optional<Input> numerator, Optional<Input> denominator, String numLabel, String denLabel) {
            if (numerator.isEmpty() || denominator.isEmpty()) {
                return new Outcome.Missing(buildMissingLabel(numerator, numLabel, denominator, denLabel));
            }
            List<Input> inputs = List.of(numerator.get(), denominator.get());
            if (!haveSameCurrency(inputs)) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            if (computeSign(denominator.get()) <= 0) {
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
            Optional<Input> end = findFirst(latest, denominator);
            Optional<Input> start = previous == null ? Optional.empty() : findFirst(previous, denominator);
            if (numerator.isEmpty() || end.isEmpty()) {
                return new Outcome.Missing(buildMissingLabel(numerator, numLabel, end, denLabel));
            }
            List<Input> inputs = new ArrayList<>(List.of(numerator.get(), end.get()));
            start.ifPresent(inputs::add);
            if (!haveSameCurrency(inputs)) {
                return new Outcome.Missing("Chiffres dans des devises différentes");
            }
            double average = start.map(s -> (s.value().doubleValue() + end.get().value().doubleValue()) / 2)
                    .orElse(end.get().value().doubleValue());
            if (computeSign(end.get()) <= 0 || average <= 0) {
                return new Outcome.Missing(capitalize(denLabel) + " : valeur négative ou nulle, ratio non interprétable");
            }
            return new Outcome.Ok(numerator.get().value().doubleValue() / average, inputs);
        }

        /** Le premier indicateur disponible parmi les candidats, pour un exercice. */
        private static Optional<Input> findFirst(Year year, Metric... candidates) {
            for (Metric metric : candidates) {
                Optional<FinancialHistory.Value> v = year.get(metric);
                if (v.isPresent()) {
                    return Optional.of(new Input(metric.name(), year.fiscalYearEnd(), v.get().value(),
                            v.get().currency(), v.get().concept()));
                }
            }
            return Optional.empty();
        }

        private static boolean isOneYearBefore(Year candidate, Year year) {
            long days = ChronoUnit.DAYS.between(candidate.fiscalYearEnd(), year.fiscalYearEnd());
            return days >= 330 && days <= 400;
        }

        /** Les montants doivent être dans la même devise (un nombre d'actions n'en a pas). */
        private static boolean haveSameCurrency(List<Input> inputs) {
            return inputs.stream().map(Input::currency).filter(Objects::nonNull).distinct().count() <= 1;
        }

        private static int computeSign(Input input) {
            return input.value().signum();
        }

        private static Input toAbsolute(Input input) {
            return new Input(input.item(), input.date(), input.value().abs(), input.currency(), input.source());
        }

        private static double extractValue(Optional<Input> input) {
            return input.map(i -> i.value().doubleValue()).orElse(0.0);
        }

        /** Paires (Optional, libellé) : liste les libellés dont l'Optional est vide. */
        private static String buildMissingLabel(Object... optionalAndLabelPairs) {
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

    /** Une valeur intermédiaire calculée (dette nette, cash-flow libre), ou la raison de son absence. */
    private record Either<T>(T value, String missingReason) {

        static <T> Either<T> of(T value) {
            return new Either<>(value, null);
        }

        static <T> Either<T> createMissing(String reason) {
            return new Either<>(null, reason);
        }

        <R> R map(java.util.function.Function<T, R> ifPresent, java.util.function.Function<String, R> ifMissing) {
            return value != null ? ifPresent.apply(value) : ifMissing.apply(missingReason);
        }
    }
}
