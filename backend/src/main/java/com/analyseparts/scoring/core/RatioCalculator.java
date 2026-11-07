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
    /** En dessous, le BPA publié (arrondi au centime) ne permet pas de retrouver le nombre d'actions. */
    static final BigDecimal MIN_EPS_FOR_SHARE_COUNT = new BigDecimal("0.25");
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
                case NET_MARGIN -> divide(first(latest, NET_INCOME_GROUP, NET_INCOME), revenue(latest),
                        "le résultat net", "le chiffre d'affaires");
                // Résultat opérationnel ÷ chiffre d'affaires
                case OPERATING_MARGIN -> divide(first(latest, OPERATING_INCOME), revenue(latest),
                        "le résultat opérationnel", "le chiffre d'affaires");
                case COST_INCOME_RATIO -> costIncome();
                case COST_OF_RISK_RATIO -> costOfRisk();
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
                case FCF_MARGIN -> freeCashFlow().map(fcf -> divide(Optional.of(fcf), revenue(latest),
                        "le cash-flow libre", "le chiffre d'affaires"), Outcome.Missing::new);
                case REVENUE_GROWTH -> growth(Calculation::revenue, "le chiffre d'affaires");
                case EPS_GROWTH -> epsGrowth();
                case PROFITABLE_YEARS -> profitableYears();
                case PAYOUT_RATIO -> payoutRatio();
                case DIVIDEND_GROWTH -> growth(Calculation::dividendsPaid, "les dividendes versés");
                // Dividendes versés ÷ capitalisation boursière
                case DIVIDEND_YIELD -> withMarketCap(cap -> dividendsPaid(latest)
                        .map(d -> divide(Optional.of(d), Optional.of(cap), "les dividendes versés", "la capitalisation"))
                        .orElse(new Outcome.Missing("Dividendes versés non publiés sous un concept standard")));
                case PER -> per();
                case EV_TO_EBIT -> balanceSheetRatio(this::evToEbit);
                // Cash-flow libre ÷ capitalisation boursière
                case FCF_YIELD -> withMarketCap(cap -> freeCashFlow().map(fcf -> divide(Optional.of(fcf),
                        Optional.of(cap), "le cash-flow libre", "la capitalisation"), Outcome.Missing::new));
                // Capitalisation ÷ capitaux propres part du groupe
                case PRICE_TO_BOOK -> withMarketCap(cap -> {
                    Outcome pb = divide(Optional.of(cap), first(latest, EQUITY_GROUP, EQUITY),
                            "la capitalisation", "les capitaux propres");
                    return pb;
                });
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
            Optional<Input> equity = first(latest, EQUITY);
            if (equity.isEmpty()) {
                return new Outcome.Missing("Capitaux propres manquants");
            }
            return netDebt().map(nd -> {
                if (!sameCurrency(List.of(nd, equity.get()))) {
                    return (Outcome) new Outcome.Missing("Chiffres dans des devises différentes");
                }
                if (signum(equity.get()) <= 0) {
                    return new Outcome.Missing("Capitaux propres négatifs ou nuls : ratio non interprétable");
                }
                List<Input> inputs = new ArrayList<>(netDebtInputs());
                inputs.add(equity.get());
                return new Outcome.Ok(nd.value().doubleValue() / equity.get().value().doubleValue(), inputs);
            }, Outcome.Missing::new);
        }

        /**
         * Dette nette = dettes financières − trésorerie, renvoyée comme une {@link Input} calculée.
         * Si seule une des deux dettes (long ou court terme) est publiée, l'autre compte pour zéro.
         */
        private Either<Input> netDebt() {
            Optional<Input> longTerm = first(latest, LONG_TERM_DEBT);
            Optional<Input> shortTerm = first(latest, SHORT_TERM_DEBT);
            if (longTerm.isEmpty() && shortTerm.isEmpty()) {
                return Either.missing("Dettes financières non publiées sous un concept standard");
            }
            Optional<Input> cash = first(latest, CASH);
            if (cash.isEmpty()) {
                return Either.missing("Trésorerie manquante");
            }
            List<Input> parts = netDebtInputs();
            if (!sameCurrency(parts)) {
                return Either.missing("Chiffres dans des devises différentes");
            }
            double value = value(longTerm) + value(shortTerm) - cash.get().value().doubleValue();
            return Either.of(new Input("NET_DEBT", latest.fiscalYearEnd(), BigDecimal.valueOf(value),
                    cash.get().currency(), "dettes financières − trésorerie"));
        }

        private List<Input> netDebtInputs() {
            List<Input> inputs = new ArrayList<>();
            first(latest, LONG_TERM_DEBT).ifPresent(inputs::add);
            first(latest, SHORT_TERM_DEBT).ifPresent(inputs::add);
            first(latest, CASH).ifPresent(inputs::add);
            return inputs;
        }

        /** Cash-flow libre = flux de trésorerie d'exploitation − investissements. */
        private Either<Input> freeCashFlow() {
            Optional<Input> ocf = first(latest, OPERATING_CASH_FLOW);
            Optional<Input> capex = first(latest, CAPEX);
            if (ocf.isEmpty() || capex.isEmpty()) {
                return Either.missing(missingLabel(ocf, "le flux de trésorerie d'exploitation", capex, "les investissements"));
            }
            if (!sameCurrency(List.of(ocf.get(), capex.get()))) {
                return Either.missing("Chiffres dans des devises différentes");
            }
            // Les investissements sont publiés en positif (décaissement) ; abs() par précaution.
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
        private Outcome costIncome() {
            Optional<Input> nbi = first(latest, NET_BANKING_INCOME);
            if (nbi.isEmpty()) {
                return new Outcome.Missing("Ratio propre aux banques : produit net bancaire non publié");
            }
            Outcome share = divide(first(latest, GROSS_OPERATING_INCOME), nbi,
                    "le résultat brut d'exploitation", "le produit net bancaire");
            return share instanceof Outcome.Ok ok ? new Outcome.Ok(1 - ok.value(), ok.inputs()) : share;
        }

        /**
         * Coût du risque ÷ produit net bancaire. Le coût du risque est publié par certaines banques ;
         * sinon il se déduit du compte de résultat : résultat brut d'exploitation − résultat
         * d'exploitation (c'est la seule ligne qui les sépare).
         */
        private Outcome costOfRisk() {
            Optional<Input> nbi = first(latest, NET_BANKING_INCOME);
            if (nbi.isEmpty()) {
                return new Outcome.Missing("Ratio propre aux banques : produit net bancaire non publié");
            }
            Optional<Input> cost = first(latest, COST_OF_RISK).map(Calculation::abs);
            if (cost.isEmpty()) {
                Optional<Input> gross = first(latest, GROSS_OPERATING_INCOME);
                Optional<Input> operating = first(latest, OPERATING_INCOME);
                if (gross.isPresent() && operating.isPresent() && sameCurrency(List.of(gross.get(), operating.get()))) {
                    BigDecimal derived = gross.get().value().subtract(operating.get().value());
                    cost = Optional.of(new Input("COST_OF_RISK", latest.fiscalYearEnd(), derived, gross.get().currency(),
                            "résultat brut d'exploitation (" + gross.get().value() + ") − résultat d'exploitation ("
                                    + operating.get().value() + ")"));
                }
            }
            return divide(cost, nbi, "le coût du risque", "le produit net bancaire");
        }

        /** Le chiffre d'affaires, ou pour une banque son équivalent : le produit net bancaire. */
        private static Optional<Input> revenue(Year year) {
            return first(year, REVENUE, NET_BANKING_INCOME);
        }

        /**
         * Les dividendes versés sur l'exercice (en positif). À défaut du montant total, dividende
         * par action × nombre d'actions, ce nombre étant retrouvé par résultat net ÷ BPA.
         */
        private static Optional<Input> dividendsPaid(Year year) {
            Optional<Input> total = first(year, DIVIDENDS_PAID);
            if (total.isPresent()) {
                return total.map(Calculation::abs);
            }
            Optional<Input> perShare = first(year, DIVIDENDS_PER_SHARE);
            Optional<Input> income = first(year, NET_INCOME_GROUP);
            Optional<Input> eps = first(year, EPS_BASIC);
            if (perShare.isEmpty() || income.isEmpty() || eps.isEmpty() || signum(eps.get()) <= 0
                    || signum(income.get()) <= 0 || !sameCurrency(List.of(perShare.get(), income.get(), eps.get()))) {
                return Optional.empty();
            }
            double shares = income.get().value().doubleValue() / eps.get().value().doubleValue();
            double amount = perShare.get().value().doubleValue() * shares;
            return Optional.of(new Input("DIVIDENDS_PAID", year.fiscalYearEnd(), BigDecimal.valueOf(Math.round(amount)),
                    perShare.get().currency(), "dividende par action (" + perShare.get().value()
                    + ") × nombre d'actions (résultat net ÷ BPA)"));
        }

        /** Dividendes versés ÷ résultat net part du groupe. */
        private Outcome payoutRatio() {
            Optional<Input> dividends = dividendsPaid(latest);
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
            return growth(y -> first(y, metric), what);
        }

        private Outcome growth(java.util.function.Function<Year, Optional<Input>> valueOf, String what) {
            List<Input> series = years.stream().map(valueOf).flatMap(Optional::stream).toList();
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
            double span = ChronoUnit.DAYS.between(oldest.date(), newest.date()) / DAYS_PER_YEAR;
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

        // --- Valorisation ---

        /** Cours ÷ bénéfice par action du dernier exercice. */
        private Outcome per() {
            if (valuation == null) {
                return new Outcome.Missing(noValuationReason);
            }
            Optional<Input> eps = first(latest, EPS_BASIC, EPS_DILUTED);
            if (eps.isEmpty()) {
                return new Outcome.Missing("Bénéfice par action manquant");
            }
            Input price = priceInput();
            if (!sameCurrency(List.of(price, eps.get()))) {
                return new Outcome.Missing("Cours et bénéfice dans des devises différentes");
            }
            if (signum(eps.get()) <= 0) {
                return new Outcome.Missing("Perte sur l'exercice : PER non significatif");
            }
            return new Outcome.Ok(price.value().doubleValue() / eps.get().value().doubleValue(),
                    List.of(price, eps.get()));
        }

        /** (Capitalisation + dette nette) ÷ résultat opérationnel. */
        private Outcome evToEbit() {
            return withMarketCap(cap -> netDebt().map(nd -> {
                Optional<Input> ebit = first(latest, OPERATING_INCOME);
                if (ebit.isEmpty()) {
                    return (Outcome) new Outcome.Missing("Résultat opérationnel manquant");
                }
                if (!sameCurrency(List.of(cap, nd, ebit.get()))) {
                    return new Outcome.Missing("Chiffres dans des devises différentes");
                }
                if (signum(ebit.get()) <= 0) {
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
        private Outcome withMarketCap(java.util.function.Function<Input, Outcome> ratio) {
            if (valuation == null) {
                return new Outcome.Missing(noValuationReason);
            }
            Input price = priceInput();
            Optional<Input> shares = shares();
            if (shares.isEmpty()) {
                return new Outcome.Missing("Nombre d'actions inconnu");
            }
            BigDecimal cap = price.value().multiply(shares.get().value());
            return ratio.apply(new Input("MARKET_CAP", price.date(), cap, price.currency(),
                    "cours (" + price.value() + ") × actions (" + shares.get().value() + ", "
                            + shares.get().source() + ")"));
        }

        private Optional<Input> shares() {
            LocalDate priceDate = priceInput().date();
            if (valuation.shares() != null && valuation.shares() > 0) {
                return Optional.of(new Input("SHARES", priceDate, BigDecimal.valueOf(valuation.shares()), null,
                        "actions admises (Euronext)"));
            }
            Optional<Input> income = first(latest, NET_INCOME_GROUP);
            Optional<Input> eps = first(latest, EPS_BASIC);
            if (income.isEmpty() || eps.isEmpty() || eps.get().value().abs().compareTo(MIN_EPS_FOR_SHARE_COUNT) < 0) {
                return Optional.empty();
            }
            BigDecimal count = income.get().value().divide(eps.get().value(), 0, java.math.RoundingMode.HALF_UP);
            return count.signum() > 0
                    ? Optional.of(new Input("SHARES", latest.fiscalYearEnd(), count, null, "résultat net ÷ BPA"))
                    : Optional.empty();
        }

        private Input priceInput() {
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
        private Outcome balanceSheetRatio(Supplier<Outcome> ratio) {
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
        private static boolean sameCurrency(List<Input> inputs) {
            return inputs.stream().map(Input::currency).filter(Objects::nonNull).distinct().count() <= 1;
        }

        private static int signum(Input input) {
            return input.value().signum();
        }

        private static Input abs(Input input) {
            return new Input(input.item(), input.date(), input.value().abs(), input.currency(), input.source());
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

    /** Une valeur intermédiaire calculée (dette nette, cash-flow libre), ou la raison de son absence. */
    private record Either<T>(T value, String missingReason) {

        static <T> Either<T> of(T value) {
            return new Either<>(value, null);
        }

        static <T> Either<T> missing(String reason) {
            return new Either<>(null, reason);
        }

        <R> R map(java.util.function.Function<T, R> ifPresent, java.util.function.Function<String, R> ifMissing) {
            return value != null ? ifPresent.apply(value) : ifMissing.apply(missingReason);
        }
    }
}
