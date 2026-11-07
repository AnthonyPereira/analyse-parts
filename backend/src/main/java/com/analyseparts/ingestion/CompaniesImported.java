package com.analyseparts.ingestion;

/**
 * Événement : une liste de sociétés vient d'être importée depuis une place de cotation.
 * <p>
 * Publié par l'import Euronext. Écouté par l'enrichissement LEI (GLEIF), et plus tard par
 * d'autres étapes (ESEF…).
 * <p>
 * Placé à la racine du module : il fait partie de son API publique.
 *
 * @param mic     place de cotation importée (ex. XPAR = Euronext Paris)
 * @param created nombre de sociétés nouvelles
 * @param updated nombre de sociétés mises à jour
 */
public record CompaniesImported(String mic, int created, int updated) {
}
