/**
 * Module <b>market</b> : les données de marché, c'est-à-dire le dernier cours de chaque société
 * et les taux de change. Les sources (Yahoo, Euronext, BCE) sont dans le module ingestion ;
 * ce module ne fait que stocker et servir les données.
 */
@ApplicationModule(displayName = "Données de marché")
package com.analyseparts.market;

import org.springframework.modulith.ApplicationModule;
