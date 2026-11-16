/**
 * Module <b>company</b> : les sociétés cotées (ISIN, LEI, nom, pays, place de cotation).
 * <p>
 * Module de base : tous les autres le lisent, il ne dépend d'aucun autre module.
 * Son API est dans deux interfaces nommées : {@code model} (la société, son secteur ICB) et
 * {@code persistence} ({@code CompanyRepository}) ; {@code web} est interne.
 */
@ApplicationModule(displayName = "Sociétés")
package com.analyseparts.company;

import org.springframework.modulith.ApplicationModule;
