/**
 * Module <b>financials</b> : les chiffres des rapports annuels et les indicateurs qui en découlent.
 * <ul>
 *   <li>stocke les faits bruts des rapports ESEF ({@link com.analyseparts.financials.model.ReportedFact}) ;</li>
 *   <li>calcule les indicateurs annuels ({@link com.analyseparts.financials.model.Metric}) à la lecture,
 *       en Java pur ({@link com.analyseparts.financials.service.MetricExtractor}).</li>
 * </ul>
 * <ul>
 *   <li>racine : {@link com.analyseparts.financials.FinancialsService}, le point d'entrée du module ;</li>
 *   <li>{@code model} et {@code event} : les données et l'événement lus par les autres modules
 *       (interfaces nommées) ;</li>
 *   <li>{@code service}, {@code persistence}, {@code web}, {@code job} : internes au module.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Données financières", allowedDependencies = {"company :: *"})
package com.analyseparts.financials;

import org.springframework.modulith.ApplicationModule;
