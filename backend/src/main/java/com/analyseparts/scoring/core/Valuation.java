package com.analyseparts.scoring.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ce qu'il faut savoir du marché pour les ratios de valorisation.
 * <p>
 * Le cours est converti dans la devise des comptes (TotalEnergies publie en dollars mais
 * cote en euros), pour comparer des montants comparables.
 *
 * @param price          cours, dans la devise des comptes
 * @param currency       devise des comptes
 * @param quotedPrice    cours tel que coté
 * @param quotedCurrency devise de cotation
 * @param priceAsOf      heure du cours
 * @param priceSource    YAHOO (séance) ou EURONEXT_CLOSE (clôture)
 * @param shares         nombre d'actions admises à la cotation (fiche Euronext), ou null
 */
public record Valuation(BigDecimal price, String currency, BigDecimal quotedPrice, String quotedCurrency,
                        Instant priceAsOf, String priceSource, Long shares) {
}
