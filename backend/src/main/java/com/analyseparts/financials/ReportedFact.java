package com.analyseparts.financials;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un fait chiffré tel que publié dans un rapport XBRL.
 *
 * @param concept     concept de la taxonomie, ex. {@code ifrs-full:Revenue}
 * @param periodStart début de période (inclus) pour un flux sur l'exercice (compte de résultat,
 *                    flux de trésorerie) ; {@code null} pour un montant à une date (bilan)
 * @param periodEnd   fin de période (incluse) ou date du bilan
 * @param unit        unité, ex. {@code iso4217:EUR} (euros), {@code iso4217:EUR/xbrli:shares} (€ par action)
 * @param value       valeur complète (pas en millions)
 * @param decimals    précision déclarée (-6 = arrondi au million), peut être {@code null}
 */
public record ReportedFact(String concept, LocalDate periodStart, LocalDate periodEnd,
                           String unit, BigDecimal value, Integer decimals) {

    public boolean isInstant() {
        return periodStart == null;
    }
}
