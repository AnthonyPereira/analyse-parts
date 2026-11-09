package com.analyseparts.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Le dernier rapport semestriel d'une société et les chiffres sur 12 mois glissants qui en
 * découlent. Affichés à côté de la note, ils n'entrent pas dans son calcul (ADR 0011, 0015).
 *
 * @param report le rapport lu, ou {@code null}
 * @param ttm    vide si le rapport manque ou ne suit pas le dernier exercice annuel
 */
public record HalfYearView(AnalysisView.State state, String error, HalfYearReport report, List<Ttm> ttm) {

    /**
     * Un chiffre sur 12 mois glissants : {@code fullYear + current - previous}, soit le dernier
     * exercice, plus le semestre publié depuis, moins le même semestre de l'exercice.
     *
     * @param fiscalYearEnd la clôture de l'exercice utilisé
     * @param periodEnd     la fin des 12 mois (celle du semestre)
     */
    public record Ttm(HalfYearReport.Item item, BigDecimal value, LocalDate fiscalYearEnd, LocalDate periodEnd,
                      BigDecimal fullYear, BigDecimal current, BigDecimal previous) {
    }
}
