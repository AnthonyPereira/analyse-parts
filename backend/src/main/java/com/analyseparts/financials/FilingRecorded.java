package com.analyseparts.financials;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Un rapport financier vient d'être enregistré : publié par {@link FinancialsService#recordFiling},
 * <b>dans la même transaction</b> que le rapport.
 * <p>
 * Spring Modulith l'écrit d'abord dans la table {@code event_publication} (l'<b>outbox</b>), puis,
 * une fois la transaction validée, l'envoie dans le topic Kafka {@value #TOPIC} avec l'ISIN pour
 * clé. Si Kafka est arrêté, l'envoi est retenté plus tard : aucun rapport n'est oublié (garantie
 * « au moins une fois »). Un même message peut donc arriver deux fois : les consommateurs doivent
 * le supporter (idempotence).
 * <p>
 * C'est aussi le <b>contrat</b> du topic : ce record est écrit en JSON, et chaque consommateur le relit.
 *
 * @param recordedAt moment de l'enregistrement : il fait partie du message, donc il est le même
 *                   si le message est rejoué (contrairement à l'heure de lecture)
 */
public record FilingRecorded(String isin, long companyId, String sourceId, LocalDate periodEnd, int factCount,
                             Instant recordedAt) {

    /** Le topic Kafka des rapports enregistrés. */
    public static final String TOPIC = "filings";
}
