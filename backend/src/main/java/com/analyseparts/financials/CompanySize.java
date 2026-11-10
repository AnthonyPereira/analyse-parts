package com.analyseparts.financials;

import java.math.BigDecimal;

/**
 * La taille d'une société d'après son dernier rapport : son chiffre d'affaires ; pour une banque
 * ou une assurance (pas de chiffre d'affaires), 5 % du total de son bilan, du même ordre que ses
 * revenus. Sert à lire d'abord les rapports des plus grosses sociétés.
 *
 * @param currency code ISO de la devise (EUR, SEK…)
 */
public record CompanySize(BigDecimal amount, String currency) {
}
