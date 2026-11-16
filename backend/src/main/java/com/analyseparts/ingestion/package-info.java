/**
 * Module <b>ingestion</b> : fait entrer les données externes, avec un sous-package par source
 * ({@code euronext}, {@code gleif}, {@code esef}, {@code prices}), chacun rangé par rôle :
 * {@code client} (récupération), {@code parser} (lecture des fichiers), {@code service} (traitement),
 * {@code persistence} (base) et {@code job} (tâches planifiées, écouteurs).
 * <p>
 * Racine : les ports appelés par analysis ({@code ReportDocuments}, {@code HalfYearDocuments}).
 * {@code model} (places de cotation) et {@code event}
 * ({@link com.analyseparts.ingestion.event.CompaniesImported}) sont ouverts aux autres modules ; les
 * autres sous-packages sont <b>internes</b>.
 */
@ApplicationModule(displayName = "Ingestion", allowedDependencies = {"company :: *", "financials", "financials :: *", "market", "market :: *"})
package com.analyseparts.ingestion;

import org.springframework.modulith.ApplicationModule;
