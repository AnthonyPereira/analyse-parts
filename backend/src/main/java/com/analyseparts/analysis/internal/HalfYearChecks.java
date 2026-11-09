package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.HalfYearReport;
import com.analyseparts.analysis.HalfYearReport.Item;
import com.analyseparts.analysis.HalfYearView.Ttm;
import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.Metric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Confronte un rapport semestriel lu par le modèle aux comptes annuels (ESEF, balisés, sûrs), puis
 * calcule les chiffres sur 12 mois glissants.
 * <p>
 * Trois vérifications, parce qu'un nombre présent sur la page peut quand même être faux (chez LVMH,
 * le modèle a collé le numéro de note devant les ventes : « 24 38644 », écrit tel quel sur la page) :
 * <ol>
 *   <li>la fin du semestre est celle qui suit le dernier exercice annuel ;</li>
 *   <li>la colonne « exercice complet », quand le tableau la donne, est égale aux comptes annuels
 *       (à 1 % près) : bonne colonne, bonne unité ;</li>
 *   <li>le chiffre d'affaires du semestre de l'an passé fait 30 à 70 % de celui de l'exercice.</li>
 *   <li>Si aucun montant n'a pu être confronté aux comptes annuels (par 2 ou par 3), rien n'est
 *       gardé : l'unité du tableau ne serait pas sûre.</li>
 * </ol>
 */
public final class HalfYearChecks {

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    static final double MIN_HALF_SHARE = 0.30;
    static final double MAX_HALF_SHARE = 0.70;

    private HalfYearChecks() {
    }

    /** La fin du semestre qui suit un exercice : 6 mois plus tard, en fin de mois (31/12 → 30/06). */
    public static LocalDate halfYearEnd(LocalDate fiscalYearEnd) {
        return YearMonth.from(fiscalYearEnd).plusMonths(6).atEndOfMonth();
    }

    /** Les lignes gardées et les raisons des rejets. */
    public record Checked(List<HalfYearReport.Line> lines, List<String> rejected) {
    }

    public static Checked check(HalfYearReader.Reading reading, FinancialHistory.Year fiscalYear) {
        List<String> rejected = new ArrayList<>(reading.rejected());
        LocalDate expected = halfYearEnd(fiscalYear.fiscalYearEnd());
        if (reading.periodEnd() == null || Math.abs(ChronoUnit.DAYS.between(expected, reading.periodEnd())) > 7) {
            rejected.add("semestre clos le " + reading.periodEnd() + ", attendu le " + expected);
            return new Checked(List.of(), rejected);
        }
        List<HalfYearReport.Line> kept = new ArrayList<>();
        boolean anchored = false; // au moins un chiffre confronté aux comptes annuels : l'unité est sûre
        for (HalfYearReader.Line l : reading.lines()) {
            Optional<BigDecimal> annual = annual(l.item(), fiscalYear);
            if (l.fullYear() != null && annual.isPresent() && !close(l.fullYear(), annual.get())) {
                rejected.add(l.item() + " : exercice complet " + l.fullYear() + " dans le rapport, " + annual.get()
                        + " dans les comptes annuels");
                continue;
            }
            if (l.item() == Item.REVENUE && annual.isPresent() && !plausibleHalf(l.previous(), annual.get())) {
                rejected.add("REVENUE : semestre précédent " + l.previous() + " pour un exercice de " + annual.get());
                continue;
            }
            boolean checkedAgainstYear = annual.isPresent() && (l.fullYear() != null || l.item() == Item.REVENUE);
            anchored |= checkedAgainstYear && l.item() != Item.EPS_BASIC; // le BPA n'a pas d'unité
            kept.add(new HalfYearReport.Line(l.item(), l.label(), l.current(), l.previous(), l.fullYear()));
        }
        if (!anchored) {
            // vu chez Covivio : un chiffre d'affaires sans équivalent dans les comptes annuels balisés
            rejected.add("aucun montant confronté aux comptes annuels : unité du tableau incertaine, rien n'est gardé");
            return new Checked(List.of(), rejected);
        }
        return new Checked(kept, rejected);
    }

    /** Le chiffre annuel correspondant : pour le chiffre d'affaires, le PNB d'une banque ou les revenus d'un assureur. */
    static Optional<BigDecimal> annual(Item item, FinancialHistory.Year year) {
        List<Metric> metrics = switch (item) {
            case REVENUE -> List.of(Metric.REVENUE, Metric.NET_BANKING_INCOME, Metric.INSURANCE_REVENUE);
            case OPERATING_INCOME -> List.of(Metric.OPERATING_INCOME);
            case NET_INCOME_GROUP -> List.of(Metric.NET_INCOME_GROUP);
            case EPS_BASIC -> List.of(Metric.EPS_BASIC);
        };
        return metrics.stream().map(year::get).flatMap(Optional::stream).findFirst().map(FinancialHistory.Value::value);
    }

    static boolean close(BigDecimal a, BigDecimal b) {
        BigDecimal gap = a.subtract(b).abs();
        BigDecimal scale = b.abs().max(a.abs());
        return scale.signum() == 0 || gap.compareTo(scale.multiply(TOLERANCE)) <= 0
                || gap.compareTo(new BigDecimal("0.01")) <= 0; // arrondi d'un BPA à deux décimales
    }

    static boolean plausibleHalf(BigDecimal half, BigDecimal year) {
        if (year.signum() <= 0) {
            return false;
        }
        double share = half.doubleValue() / year.doubleValue();
        return share >= MIN_HALF_SHARE && share <= MAX_HALF_SHARE;
    }

    /** Les chiffres sur 12 mois : dernier exercice + semestre publié − même semestre de l'exercice. */
    public static List<Ttm> ttm(HalfYearReport report, FinancialHistory.Year fiscalYear) {
        List<Ttm> result = new ArrayList<>();
        for (HalfYearReport.Line l : report.lines()) {
            annual(l.item(), fiscalYear).ifPresent(a -> result.add(new Ttm(l.item(),
                    a.add(l.current()).subtract(l.previous()), fiscalYear.fiscalYearEnd(), report.periodEnd(),
                    a, l.current(), l.previous())));
        }
        return result;
    }
}
