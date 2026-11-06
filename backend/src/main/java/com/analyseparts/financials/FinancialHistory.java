package com.analyseparts.financials;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Indicateurs d'une société, exercice par exercice (le plus récent en premier). */
public record FinancialHistory(List<Year> years) {

    /**
     * Une valeur d'indicateur, avec sa provenance : utile pour expliquer un score.
     *
     * @param value    montant
     * @param currency devise (EUR, USD…)
     * @param concept  concept XBRL utilisé
     * @param filing   rapport d'où vient la valeur (le plus récent qui la contient)
     */
    public record Value(BigDecimal value, String currency, String concept, String filing) {
    }

    public record Year(LocalDate fiscalYearEnd, Map<Metric, Value> metrics) {

        public Optional<Value> get(Metric metric) {
            return Optional.ofNullable(metrics.get(metric));
        }
    }
}
