/**
 * Lecture des rapports annuels par un modèle de langage local (Ollama) : ce que fait la société,
 * ses avantages concurrentiels et ses risques, chaque point appuyé par une citation vérifiée du
 * rapport. Affiché à côté de la note, il n'entre <b>jamais</b> dans son calcul (ADR 0011).
 * <p>
 * Aucun autre module ne l'appelle : la racine est vide, {@code model} est ouvert, le reste
 * ({@code service}, {@code client}, {@code persistence}, {@code job}, {@code web}) est interne.
 */
@ApplicationModule(displayName = "Lecture des rapports (LLM)", allowedDependencies = {"company :: *", "financials", "financials :: *", "ingestion", "ingestion :: *", "market", "market :: *"})
package com.analyseparts.analysis;

import org.springframework.modulith.ApplicationModule;
