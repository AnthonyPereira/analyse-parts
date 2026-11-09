package com.analyseparts.scoring.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static com.analyseparts.scoring.core.Ratio.CASH_CONVERSION;
import static com.analyseparts.scoring.core.Ratio.COST_INCOME_RATIO;
import static com.analyseparts.scoring.core.Ratio.COST_OF_RISK_RATIO;
import static com.analyseparts.scoring.core.Ratio.CURRENT_RATIO;
import static com.analyseparts.scoring.core.Ratio.DIVIDEND_GROWTH;
import static com.analyseparts.scoring.core.Ratio.DIVIDEND_YIELD;
import static com.analyseparts.scoring.core.Ratio.EV_TO_EBIT;
import static com.analyseparts.scoring.core.Ratio.FCF_YIELD;
import static com.analyseparts.scoring.core.Ratio.PER;
import static com.analyseparts.scoring.core.Ratio.PRICE_TO_BOOK;
import static com.analyseparts.scoring.core.Ratio.EPS_GROWTH;
import static com.analyseparts.scoring.core.Ratio.EQUITY_RATIO;
import static com.analyseparts.scoring.core.Ratio.FCF_MARGIN;
import static com.analyseparts.scoring.core.Ratio.NET_DEBT_TO_EQUITY;
import static com.analyseparts.scoring.core.Ratio.NET_MARGIN;
import static com.analyseparts.scoring.core.Ratio.OPERATING_MARGIN;
import static com.analyseparts.scoring.core.Ratio.PAYOUT_RATIO;
import static com.analyseparts.scoring.core.Ratio.PROFITABLE_YEARS;
import static com.analyseparts.scoring.core.Ratio.REVENUE_GROWTH;
import static com.analyseparts.scoring.core.Ratio.ROA;
import static com.analyseparts.scoring.core.Ratio.ROE;

/**
 * Profils d'investisseur : chacun donne un poids à chaque ratio (total 100).
 * <p>
 * Chaque profil a aussi des <b>poids pour les banques</b> : leur dette est leur matière première
 * et elles n'ont pas de chiffre d'affaires, donc l'endettement, la liquidité, la valeur
 * d'entreprise et le cash-flow libre n'ont pas de sens pour elles. Ces poids posent les mêmes
 * questions (rentable ? solide ? bon marché ?) avec les indicateurs du métier bancaire :
 * coefficient d'exploitation et coût du risque.
 * <p>
 * Les <b>assurances</b> ont aussi leurs poids : comme les banques, leur bilan n'a ni dette
 * financière ni liquidité au sens industriel (il est fait des placements qui couvrent les
 * contrats), et leurs indicateurs propres (ratio combiné, ratio de solvabilité) ne sont pas
 * balisés dans les rapports ESEF. On les note donc sur la rentabilité, sa régularité, la
 * croissance, le dividende et le prix, sans les ratios bancaires.
 * Le profil Value est le seul qui utilise le cours de bourse : il compare le prix au bénéfice.
 */
public enum Profile {

    QUALITY("Qualité", "Des entreprises très rentables, solides, dont les bénéfices sont du vrai argent.",
            weights(ROE, 20, OPERATING_MARGIN, 15, NET_MARGIN, 10, ROA, 5,
                    CASH_CONVERSION, 10, FCF_MARGIN, 10,
                    NET_DEBT_TO_EQUITY, 10, EQUITY_RATIO, 5, CURRENT_RATIO, 5,
                    PROFITABLE_YEARS, 10),
            weights(ROE, 25, COST_INCOME_RATIO, 20, COST_OF_RISK_RATIO, 15, ROA, 10, NET_MARGIN, 10,
                    PROFITABLE_YEARS, 20),
            weights(ROE, 30, NET_MARGIN, 20, ROA, 15, PROFITABLE_YEARS, 25, EPS_GROWTH, 10)),
    GROWTH("Croissance", "Des entreprises dont les ventes et les bénéfices augmentent vite, sans s'endetter pour cela.",
            weights(REVENUE_GROWTH, 35, EPS_GROWTH, 30, ROE, 15, OPERATING_MARGIN, 10, NET_DEBT_TO_EQUITY, 10),
            weights(REVENUE_GROWTH, 35, EPS_GROWTH, 30, ROE, 20, COST_INCOME_RATIO, 15),
            weights(REVENUE_GROWTH, 35, EPS_GROWTH, 35, ROE, 20, NET_MARGIN, 10)),
    DIVIDEND("Dividende", "Des entreprises qui versent un dividende régulier, en hausse, et qu'elles peuvent se permettre.",
            weights(DIVIDEND_YIELD, 20, PAYOUT_RATIO, 20, DIVIDEND_GROWTH, 20, PROFITABLE_YEARS, 10,
                    NET_DEBT_TO_EQUITY, 10, FCF_MARGIN, 10, CASH_CONVERSION, 10),
            weights(DIVIDEND_YIELD, 25, PAYOUT_RATIO, 20, DIVIDEND_GROWTH, 20, PROFITABLE_YEARS, 15,
                    COST_OF_RISK_RATIO, 10, ROE, 10),
            weights(DIVIDEND_YIELD, 25, PAYOUT_RATIO, 25, DIVIDEND_GROWTH, 20, PROFITABLE_YEARS, 20, ROE, 10)),
    VALUE("Value", "Des entreprises bon marché par rapport à ce qu'elles gagnent, sans dette excessive "
            + "(pour éviter les « pièges à value » : pas chères parce qu'en mauvaise santé).",
            weights(PER, 25, EV_TO_EBIT, 25, FCF_YIELD, 20, PRICE_TO_BOOK, 10, DIVIDEND_YIELD, 10,
                    NET_DEBT_TO_EQUITY, 10),
            weights(PER, 35, PRICE_TO_BOOK, 30, DIVIDEND_YIELD, 20, COST_OF_RISK_RATIO, 15),
            weights(PER, 40, PRICE_TO_BOOK, 30, DIVIDEND_YIELD, 30));

    /** Comment une société est notée : les poids généraux, ceux des banques ou ceux des assurances. */
    public enum Book {
        STANDARD, BANK, INSURER
    }

    private final String label;
    private final String description;
    private final Map<Ratio, Integer> weights;
    private final Map<Ratio, Integer> bankWeights;
    private final Map<Ratio, Integer> insurerWeights;

    Profile(String label, String description, Map<Ratio, Integer> weights, Map<Ratio, Integer> bankWeights,
            Map<Ratio, Integer> insurerWeights) {
        this.label = label;
        this.description = description;
        this.weights = weights;
        this.bankWeights = bankWeights;
        this.insurerWeights = insurerWeights;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public Map<Ratio, Integer> weights() {
        return weights;
    }

    /** Les poids appliqués aux banques. */
    public Map<Ratio, Integer> bankWeights() {
        return bankWeights;
    }

    /** Les poids appliqués aux assurances. */
    public Map<Ratio, Integer> insurerWeights() {
        return insurerWeights;
    }

    public Map<Ratio, Integer> weights(Book book) {
        return switch (book) {
            case STANDARD -> weights;
            case BANK -> bankWeights;
            case INSURER -> insurerWeights;
        };
    }

    private static Map<Ratio, Integer> weights(Object... ratioWeightPairs) {
        Map<Ratio, Integer> weights = new EnumMap<>(Ratio.class);
        for (int i = 0; i < ratioWeightPairs.length; i += 2) {
            weights.put((Ratio) ratioWeightPairs[i], (Integer) ratioWeightPairs[i + 1]);
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100) {
            throw new IllegalStateException("Les poids d'un profil doivent faire 100, pas " + total);
        }
        return Collections.unmodifiableMap(weights);
    }
}
