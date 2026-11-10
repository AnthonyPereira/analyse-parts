package com.analyseparts.ingestion.esef;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Quand faut-il réinterroger filings.xbrl.org pour une société ?
 * <p>
 * Le site limite le nombre de requêtes : on ne redemande que si un nouveau rapport est
 * probable.
 * <ul>
 *   <li>jamais consultée : oui ;</li>
 *   <li>avec un dernier exercice connu : un nouveau rapport est attendu
 *       {@value #NEXT_REPORT_MONTHS} mois après sa clôture (12 mois d'exercice, puis la
 *       publication, au plus tard 4 mois après). Dès ce moment, on vérifie chaque semaine ;</li>
 *   <li>sans aucun rapport (souvent une petite société absente du site) : une fois par mois.</li>
 * </ul>
 */
final class EsefRefreshPolicy {

    static final int NEXT_REPORT_MONTHS = 13;
    static final Duration RECHECK_WHEN_EXPECTED = Duration.ofDays(7);
    static final Duration RECHECK_WITHOUT_FILING = Duration.ofDays(30);

    private EsefRefreshPolicy() {
    }

    /**
     * @param lastChecked      dernière consultation réussie de l'index (ou {@code null})
     * @param latestPeriodEnd  clôture du rapport le plus récent en base (ou {@code null})
     * @param ignoreRecentCheck {@code true} pour une relance manuelle : on ne tient pas compte
     *                         de la date de dernière consultation
     */
    static boolean isDue(Instant lastChecked, LocalDate latestPeriodEnd, Instant now, boolean ignoreRecentCheck) {
        if (lastChecked == null) {
            return true;
        }
        if (latestPeriodEnd == null) {
            return ignoreRecentCheck || wasCheckedBefore(lastChecked, now, RECHECK_WITHOUT_FILING);
        }
        return isReportExpected(latestPeriodEnd, now)
                && (ignoreRecentCheck || wasCheckedBefore(lastChecked, now, RECHECK_WHEN_EXPECTED));
    }

    /** Un nouveau rapport annuel est attendu : {@value #NEXT_REPORT_MONTHS} mois ont passé depuis la dernière clôture connue. */
    static boolean isReportExpected(LocalDate latestPeriodEnd, Instant now) {
        if (latestPeriodEnd == null) {
            return false;
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return !latestPeriodEnd.plusMonths(NEXT_REPORT_MONTHS).isAfter(today);
    }

    private static boolean wasCheckedBefore(Instant lastChecked, Instant now, Duration delay) {
        return !lastChecked.plus(delay).isAfter(now);
    }
}
