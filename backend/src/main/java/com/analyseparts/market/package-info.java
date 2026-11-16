/**
 * Module <b>market</b> : les données de marché, c'est-à-dire le dernier cours de chaque société
 * et les taux de change. Les sources (Yahoo, Euronext, BCE) sont dans le module ingestion ;
 * ce module ne fait que stocker et servir les données.
 * <ul>
 *   <li>racine : {@code MarketDataService} (lire et enregistrer les cours) et le port
 *       {@code IntradaySource}, implémenté dans ingestion ;</li>
 *   <li>{@code model} et {@code event} : ouverts aux autres modules ;</li>
 *   <li>{@code persistence}, {@code web} : internes.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Données de marché")
package com.analyseparts.market;

import org.springframework.modulith.ApplicationModule;
