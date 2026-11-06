package com.analyseparts.scoring.core;

import java.time.LocalDate;

/**
 * Fraîcheur des chiffres sur lesquels repose un score.
 * <p>
 * Une société cotée publie son rapport annuel au plus tard 4 mois après la clôture : 12 mois
 * d'exercice + 4 mois = 16 mois. Au-delà, un rapport plus récent existe sûrement mais notre
 * source ne l'a pas encore. Un score calculé sur des chiffres vieux de plus de 2 ans
 * (ex. Kering : dernier rapport disponible 2023, croissance mesurée depuis l'année Covid 2020)
 * serait trompeur : on n'en donne pas.
 */
public enum Freshness {
    /** Le dernier rapport est le plus récent qui puisse exister. */
    UP_TO_DATE,
    /** Un rapport plus récent existe probablement : score calculé, mais signalé. */
    LATE,
    /** Chiffres trop anciens : pas de score. */
    OBSOLETE;

    static final int UP_TO_DATE_MONTHS = 16;
    static final int OBSOLETE_MONTHS = 28;

    public static Freshness of(LocalDate fiscalYearEnd, LocalDate asOf) {
        if (fiscalYearEnd == null) {
            return OBSOLETE;
        }
        if (!fiscalYearEnd.plusMonths(UP_TO_DATE_MONTHS).isBefore(asOf)) {
            return UP_TO_DATE;
        }
        return fiscalYearEnd.plusMonths(OBSOLETE_MONTHS).isBefore(asOf) ? OBSOLETE : LATE;
    }
}
