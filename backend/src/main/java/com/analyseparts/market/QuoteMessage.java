package com.analyseparts.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un cours tel qu'il circule dans le topic Kafka {@code quotes}, puis jusqu'au navigateur.
 * <p>
 * C'est un <b>contrat</b> : producteur et consommateurs ne partagent que ce format JSON. On peut
 * ajouter un champ (les anciens lecteurs l'ignorent), mais pas en retirer ni en renommer un sans
 * casser les lecteurs existants.
 *
 * @param isin          clé du message : tous les cours d'une société vont dans la même partition
 * @param previousClose clôture de la veille, pour la variation du jour (peut être nul)
 */
public record QuoteMessage(String isin, BigDecimal price, String currency, Instant asOf, String source,
                           BigDecimal previousClose) {
}
