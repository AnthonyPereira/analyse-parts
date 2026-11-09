package com.analyseparts.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Les chiffres du compte de résultat d'un rapport financier semestriel, recopiés par le modèle de
 * langage puis vérifiés (ADR 0015). Montants en euros (unité du tableau déjà appliquée), sauf le
 * bénéfice par action, en euros par action.
 *
 * @param periodEnd fin du semestre (30 juin pour un exercice clos le 31 décembre)
 * @param lines     les lignes gardées ; les autres ont échoué à une vérification
 */
public record HalfYearReport(LocalDate periodEnd, LocalDate publishedOn, String title, String sourceUrl,
                             String model, Instant createdAt, List<Line> lines) {

    /** Les indicateurs lus : un sous-ensemble de ceux des comptes annuels. */
    public enum Item {
        /** Chiffre d'affaires, ou produit net bancaire, ou revenus d'assurance. */
        REVENUE,
        OPERATING_INCOME,
        NET_INCOME_GROUP,
        EPS_BASIC
    }

    /**
     * Une ligne du compte de résultat.
     *
     * @param label    son intitulé dans le rapport (« Chiffre d'affaires », « Ventes »…)
     * @param current  le semestre publié
     * @param previous le même semestre, un an plus tôt
     * @param fullYear l'exercice complet précédent, quand le tableau le donne (sert à vérifier)
     */
    public record Line(Item item, String label, BigDecimal current, BigDecimal previous, BigDecimal fullYear) {
    }
}
