package com.analyseparts.ingestion.esef;

import com.analyseparts.financials.ReportedFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.XbrlFact;
import com.analyseparts.ingestion.esef.FilingsXbrlClient.XbrlJson;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extrait d'un rapport xBRL-JSON les faits chiffrés <b>totaux</b>.
 * <p>
 * Un même concept apparaît souvent plusieurs fois : le total, puis sa ventilation selon un
 * "axe" (par secteur, par composante des capitaux propres…). On ne garde que les faits sans
 * axe, c'est-à-dire ceux dont les dimensions se limitent à la liste {@link #PLAIN_DIMENSIONS}.
 * Les doublons exacts (même chiffre repris dans plusieurs tableaux) sont fusionnés.
 */
public final class XbrlJsonParser {

    private static final Set<String> PLAIN_DIMENSIONS =
            Set.of("concept", "entity", "period", "unit", "language", "noteId");

    private XbrlJsonParser() {
    }

    public static List<ReportedFact> extractTotals(XbrlJson json) {
        if (json == null || json.facts() == null) {
            return List.of();
        }
        Map<String, ReportedFact> unique = new LinkedHashMap<>();
        for (XbrlFact fact : json.facts().values()) {
            Map<String, String> dims = fact.dimensions();
            if (dims == null || dims.get("unit") == null || fact.value() == null
                    || !PLAIN_DIMENSIONS.containsAll(dims.keySet())) {
                continue; // texte, fait ventilé selon un axe, ou valeur absente
            }
            BigDecimal value;
            try {
                value = new BigDecimal(fact.value());
            } catch (NumberFormatException e) {
                continue;
            }
            Period period = Period.parse(dims.get("period"));
            ReportedFact rf = new ReportedFact(dims.get("concept"), period.start(), period.end(),
                    dims.get("unit"), value, parseDecimals(fact.decimals()));
            unique.putIfAbsent(rf.concept() + '|' + rf.periodStart() + '|' + rf.periodEnd() + '|' + rf.unit(), rf);
        }
        return new ArrayList<>(unique.values());
    }

    private static Integer parseDecimals(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        return null; // "INF" (valeur exacte) ou absent
    }

    /**
     * Période xBRL-JSON. Les dates de fin sont <b>exclusives</b> à minuit :
     * {@code 2025-01-01T00:00:00/2026-01-01T00:00:00} = exercice du 01/01/2025 au 31/12/2025 inclus,
     * et le bilan {@code 2026-01-01T00:00:00} = situation au soir du 31/12/2025.
     */
    record Period(LocalDate start, LocalDate end) {

        static Period parse(String raw) {
            int slash = raw.indexOf('/');
            if (slash < 0) {
                return new Period(null, parseEndDate(raw));
            }
            return new Period(parseStartDate(raw.substring(0, slash)), parseEndDate(raw.substring(slash + 1)));
        }

        private static LocalDate parseStartDate(String s) {
            return s.length() == 10 ? LocalDate.parse(s) : LocalDateTime.parse(s).toLocalDate();
        }

        private static LocalDate parseEndDate(String s) {
            if (s.length() == 10) {
                return LocalDate.parse(s);
            }
            if (s.endsWith("T24:00:00")) {
                return LocalDate.parse(s.substring(0, 10));
            }
            LocalDateTime t = LocalDateTime.parse(s);
            return t.toLocalTime().equals(LocalTime.MIDNIGHT) ? t.toLocalDate().minusDays(1) : t.toLocalDate();
        }
    }
}
