/**
 * Module <b>financials</b> : les chiffres des rapports annuels et les indicateurs qui en découlent.
 * <ul>
 *   <li>stocke les faits bruts des rapports ESEF ({@link com.analyseparts.financials.ReportedFact}) ;</li>
 *   <li>calcule les indicateurs annuels ({@link com.analyseparts.financials.Metric}) à la lecture,
 *       en Java pur ({@link com.analyseparts.financials.MetricExtractor}).</li>
 * </ul>
 * Le sous-package {@code internal} (entités, accès base) n'est pas visible des autres modules.
 */
@ApplicationModule(displayName = "Données financières", allowedDependencies = "company")
package com.analyseparts.financials;

import org.springframework.modulith.ApplicationModule;
