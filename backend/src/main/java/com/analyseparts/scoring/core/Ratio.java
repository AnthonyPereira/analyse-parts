package com.analyseparts.scoring.core;

import static com.analyseparts.scoring.core.RatioFamily.CASH;
import static com.analyseparts.scoring.core.RatioFamily.DIVIDEND;
import static com.analyseparts.scoring.core.RatioFamily.GROWTH;
import static com.analyseparts.scoring.core.RatioFamily.PROFITABILITY;
import static com.analyseparts.scoring.core.RatioFamily.REGULARITY;
import static com.analyseparts.scoring.core.RatioFamily.SOLIDITY;
import static com.analyseparts.scoring.core.RatioFamily.VALUATION;

/**
 * Les ratios calculés pour chaque société, avec leur barème.
 * <p>
 * Un ratio divise un chiffre par un autre : il ne dépend ni de la taille de l'entreprise, ni de
 * sa devise, ce qui permet de comparer L'Oréal et une petite société. Les formules sont dans
 * {@link RatioCalculator}, les explications détaillées dans {@code docs/ratios.md}.
 * <p>
 * Les barèmes sont des repères généralistes. Ceux marqués {@code sectorAdjusted} sont étirés
 * ou resserrés selon le niveau normal du secteur ({@link SectorCalibration}, ADR 0004) : une
 * marge de 4 % est faible pour un éditeur de logiciels, bonne pour un distributeur.
 */
public enum Ratio {

    // --- Rentabilité ---

    NET_MARGIN(PROFITABILITY, Unit.PERCENT, true, "Marge nette",
            "Sur 100 € de ventes, ce qu'il reste en bénéfice une fois toutes les charges et l'impôt payés.",
            Scale.of(0, 0, 0.05, 40, 0.10, 70, 0.20, 100)),
    OPERATING_MARGIN(PROFITABILITY, Unit.PERCENT, true, "Marge opérationnelle",
            "Sur 100 € de ventes, ce que rapporte le métier lui-même, avant frais financiers et impôt.",
            Scale.of(0, 0, 0.08, 40, 0.15, 70, 0.25, 100)),
    /** Non ajusté : quel que soit le secteur, l'actionnaire attend au moins le coût du capital (~8-10 %). */
    ROE(PROFITABILITY, Unit.PERCENT, false, "Rentabilité des capitaux propres (ROE)",
            "Ce que rapporte chaque euro laissé par les actionnaires dans l'entreprise.",
            Scale.of(0, 0, 0.08, 40, 0.15, 75, 0.25, 100)),
    ROA(PROFITABILITY, Unit.PERCENT, true, "Rentabilité de l'actif (ROA)",
            "Ce que rapporte chaque euro d'actif, qu'il soit financé par les actionnaires ou par la dette.",
            Scale.of(0, 0, 0.02, 30, 0.05, 60, 0.10, 100)),

    // --- Solidité financière ---

    NET_DEBT_TO_EQUITY(SOLIDITY, Unit.MULTIPLE, true, "Dette nette / capitaux propres (gearing)",
            "La dette financière, moins la trésorerie, rapportée à ce qui appartient aux actionnaires. "
                    + "Négatif : l'entreprise a plus de trésorerie que de dettes.",
            Scale.of(0, 100, 0.5, 70, 1.0, 40, 2.0, 0)),
    EQUITY_RATIO(SOLIDITY, Unit.PERCENT, true, "Autonomie financière",
            "La part du bilan financée par les actionnaires plutôt que par des dettes.",
            Scale.of(0.10, 0, 0.30, 60, 0.50, 100)),
    CURRENT_RATIO(SOLIDITY, Unit.MULTIPLE, true, "Liquidité générale",
            "Ce qui rentre en argent sous un an, comparé à ce qu'il faut payer sous un an.",
            Scale.of(0.8, 0, 1.0, 40, 1.5, 80, 2.0, 100)),

    // --- Qualité du cash ---

    CASH_CONVERSION(CASH, Unit.MULTIPLE, false, "Conversion du résultat en cash",
            "L'argent réellement encaissé par l'activité, comparé au bénéfice comptable. "
                    + "Proche de 1 ou plus : le bénéfice est du vrai argent.",
            Scale.of(0.5, 0, 0.8, 50, 1.0, 80, 1.2, 100)),
    FCF_MARGIN(CASH, Unit.PERCENT, true, "Marge de cash-flow libre",
            "Sur 100 € de ventes, l'argent qu'il reste après avoir payé l'activité et les investissements : "
                    + "c'est ce qui peut servir à rembourser, racheter ou verser des dividendes.",
            Scale.of(0, 0, 0.05, 50, 0.10, 80, 0.15, 100)),

    // --- Croissance ---

    REVENUE_GROWTH(GROWTH, Unit.PERCENT, false, "Croissance annuelle du chiffre d'affaires",
            "De combien les ventes ont augmenté en moyenne chaque année sur l'historique disponible.",
            Scale.of(-0.05, 0, 0, 30, 0.05, 60, 0.10, 85, 0.15, 100)),
    EPS_GROWTH(GROWTH, Unit.PERCENT, false, "Croissance annuelle du bénéfice par action",
            "De combien le bénéfice revenant à chaque action a augmenté en moyenne chaque année.",
            Scale.of(-0.05, 0, 0, 30, 0.07, 65, 0.15, 100)),

    // --- Régularité ---

    PROFITABLE_YEARS(REGULARITY, Unit.PERCENT, false, "Exercices bénéficiaires",
            "La part des exercices disponibles qui se terminent par un bénéfice.",
            Scale.of(0, 0, 0.75, 50, 1.0, 100)),

    // --- Dividende ---

    PAYOUT_RATIO(DIVIDEND, Unit.PERCENT, false, "Taux de distribution",
            "La part du bénéfice versée en dividendes. Trop bas : peu de revenu pour l'actionnaire ; "
                    + "trop haut : le dividende n'est pas soutenable.",
            Scale.of(0, 0, 0.30, 80, 0.40, 100, 0.60, 100, 0.80, 60, 1.0, 20, 1.5, 0)),
    DIVIDEND_GROWTH(DIVIDEND, Unit.PERCENT, false, "Croissance annuelle des dividendes versés",
            "De combien le montant total des dividendes versés a augmenté en moyenne chaque année.",
            Scale.of(-0.05, 0, 0, 40, 0.05, 75, 0.10, 100)),
    DIVIDEND_YIELD(DIVIDEND, Unit.PERCENT, false, "Rendement du dividende",
            "Les dividendes versés sur le dernier exercice, rapportés à la valeur en bourse : "
                    + "ce que rapporterait chaque année une action achetée aujourd'hui, si le dividende se maintient.",
            Scale.of(0, 0, 0.02, 40, 0.04, 80, 0.06, 100)),

    // --- Valorisation : il faut le cours de bourse ---

    PER(VALUATION, Unit.MULTIPLE, true, "PER (cours / bénéfice par action)",
            "Combien d'années de bénéfice actuel il faudrait pour « rembourser » le prix de l'action. "
                    + "Plus il est bas, moins l'action est chère.",
            Scale.of(5, 100, 10, 85, 15, 60, 20, 40, 30, 15, 40, 0)),
    EV_TO_EBIT(VALUATION, Unit.MULTIPLE, true, "Valeur d'entreprise / résultat opérationnel",
            "Le prix de toute l'entreprise (actions + dette nette) rapporté à ce que rapporte son métier. "
                    + "Comme le PER, mais sans être faussé par l'endettement.",
            Scale.of(4, 100, 8, 80, 12, 55, 18, 25, 25, 0)),
    FCF_YIELD(VALUATION, Unit.PERCENT, false, "Rendement du cash-flow libre",
            "Le cash-flow libre de l'exercice rapporté à la valeur en bourse : le « rendement » réel "
                    + "de l'entreprise pour celui qui l'achèterait entière au prix du marché.",
            Scale.of(0, 0, 0.03, 40, 0.06, 75, 0.10, 100)),
    PRICE_TO_BOOK(VALUATION, Unit.MULTIPLE, true, "Cours / valeur comptable",
            "La valeur en bourse comparée aux capitaux propres comptables. Sous 1, le marché paie "
                    + "l'entreprise moins que ce que disent ses comptes.",
            Scale.of(0.8, 100, 1.5, 75, 3, 40, 6, 10, 10, 0));

    /** Comment afficher la valeur. */
    public enum Unit {
        /** Proportion : 0,153 s'affiche 15,3 %. */
        PERCENT,
        /** Multiple : 1,8 s'affiche 1,8x. */
        MULTIPLE
    }

    private final RatioFamily family;
    private final Unit unit;
    private final boolean sectorAdjusted;
    private final String label;
    private final String description;
    private final Scale scale;

    Ratio(RatioFamily family, Unit unit, boolean sectorAdjusted, String label, String description, Scale scale) {
        this.family = family;
        this.unit = unit;
        this.sectorAdjusted = sectorAdjusted;
        this.label = label;
        this.description = description;
        this.scale = scale;
    }

    public RatioFamily family() {
        return family;
    }

    public Unit unit() {
        return unit;
    }

    /** Le barème s'adapte-t-il au niveau normal du secteur ? */
    public boolean sectorAdjusted() {
        return sectorAdjusted;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public Scale scale() {
        return scale;
    }
}
