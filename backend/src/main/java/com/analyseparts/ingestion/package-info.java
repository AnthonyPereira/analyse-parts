/**
 * Module <b>ingestion</b> : fait entrer les données externes, avec un sous-package par source
 * ({@code euronext}, {@code gleif}, puis {@code esef}, {@code prices}, {@code news}…).
 * <p>
 * API publique (package racine) : les événements comme {@link com.analyseparts.ingestion.CompaniesImported}.
 * Les sous-packages sont <b>internes</b> : Spring Modulith interdit aux autres modules de les utiliser.
 */
@ApplicationModule(displayName = "Ingestion", allowedDependencies = "company")
package com.analyseparts.ingestion;

import org.springframework.modulith.ApplicationModule;
