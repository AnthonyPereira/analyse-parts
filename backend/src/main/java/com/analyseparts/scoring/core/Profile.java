package com.analyseparts.scoring.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static com.analyseparts.scoring.core.Ratio.CASH_CONVERSION;
import static com.analyseparts.scoring.core.Ratio.CURRENT_RATIO;
import static com.analyseparts.scoring.core.Ratio.DIVIDEND_GROWTH;
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
 * Le profil « Value » (acheter des sociétés bon marché) viendra avec les cours de bourse :
 * il compare le prix au bénéfice, ce que les rapports annuels seuls ne permettent pas.
 */
public enum Profile {

    QUALITY("Qualité", "Des entreprises très rentables, solides, dont les bénéfices sont du vrai argent.",
            weights(ROE, 20, OPERATING_MARGIN, 15, NET_MARGIN, 10, ROA, 5,
                    CASH_CONVERSION, 10, FCF_MARGIN, 10,
                    NET_DEBT_TO_EQUITY, 10, EQUITY_RATIO, 5, CURRENT_RATIO, 5,
                    PROFITABLE_YEARS, 10)),
    GROWTH("Croissance", "Des entreprises dont les ventes et les bénéfices augmentent vite, sans s'endetter pour cela.",
            weights(REVENUE_GROWTH, 35, EPS_GROWTH, 30, ROE, 15, OPERATING_MARGIN, 10, NET_DEBT_TO_EQUITY, 10)),
    DIVIDEND("Dividende", "Des entreprises qui versent un dividende régulier, en hausse, et qu'elles peuvent se permettre.",
            weights(PAYOUT_RATIO, 25, DIVIDEND_GROWTH, 25, PROFITABLE_YEARS, 15,
                    NET_DEBT_TO_EQUITY, 15, FCF_MARGIN, 10, CASH_CONVERSION, 10));

    private final String label;
    private final String description;
    private final Map<Ratio, Integer> weights;

    Profile(String label, String description, Map<Ratio, Integer> weights) {
        this.label = label;
        this.description = description;
        this.weights = weights;
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
