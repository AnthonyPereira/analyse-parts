package com.analyseparts.market;

import java.util.List;
import java.util.Optional;

/**
 * Où trouver la courbe de la séance du jour. C'est un « port » au sens de l'architecture
 * hexagonale : le module market dit ce dont il a besoin, le module ingestion fournit
 * l'implémentation (Yahoo). Ainsi market ne dépend pas d'ingestion.
 */
public interface IntradaySource {

    /** Les cours de la dernière séance (un point toutes les 5 minutes), vide si inconnus. */
    Optional<Intraday> fetchIntraday(long companyId);

    record Intraday(String currency, java.math.BigDecimal previousClose, List<PricePoint> points) {
    }
}
