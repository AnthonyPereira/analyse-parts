package com.analyseparts.financials;

import java.time.LocalDate;

/**
 * Description d'un rapport annuel à enregistrer.
 *
 * @param sourceId   identifiant unique chez la source (filings.xbrl.org : fxo_id)
 * @param periodEnd  date de clôture de l'exercice
 * @param country    pays de dépôt indiqué par la source
 * @param jsonUrl    adresse du rapport au format xBRL-JSON
 * @param errorCount nombre d'erreurs de validation signalées par la source
 */
public record FilingData(String sourceId, LocalDate periodEnd, String country, String jsonUrl, Integer errorCount) {
}
