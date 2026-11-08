package com.analyseparts.financials;

import java.time.LocalDate;

/**
 * Un rapport annuel enregistré, vu de l'extérieur du module : de quoi le retrouver chez sa source.
 *
 * @param sourceId  identifiant unique chez la source
 * @param periodEnd date de clôture de l'exercice
 * @param url       adresse du rapport : paquet ZIP de l'AMF, ou xBRL-JSON de filings.xbrl.org
 */
public record FilingRef(String sourceId, LocalDate periodEnd, String url) {
}
