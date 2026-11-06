/**
 * Module <b>scoring</b> : transforme les indicateurs d'une société en ratios, puis en un
 * score de 0 à 100 selon un profil d'investisseur, avec le détail de chaque contribution.
 * <ul>
 *   <li>{@code core} : le calcul, en Java pur (ni Spring, ni base de données), cf. ADR 0001 et 0003 ;</li>
 *   <li>package racine : le service Spring et l'API REST autour de ce calcul.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Score", allowedDependencies = {"company", "financials", "market"})
package com.analyseparts.scoring;

import org.springframework.modulith.ApplicationModule;
