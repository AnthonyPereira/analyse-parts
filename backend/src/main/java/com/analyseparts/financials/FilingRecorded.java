package com.analyseparts.financials;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Un rapport financier vient d'être enregistré : publié par {@link FinancialsService#recordFiling},
 * <b>dans la même transaction</b> que le rapport.
 * <p>
 * Spring Modulith l'écrit dans la table {@code event_publication} avec le rapport : les deux
 * existent, ou aucun. Chaque listener (l'historique des notes, plus tard l'analyse par le LLM local)
 * est ensuite appelé après le commit, et rejoué s'il n'a pas abouti. Un listener peut donc recevoir
 * le même événement deux fois : il doit le supporter (idempotence).
 *
 * @param recordedAt moment de l'enregistrement : il fait partie de l'événement, donc il est le même
 *                   si l'événement est rejoué (contrairement à l'heure de traitement)
 */
public record FilingRecorded(String isin, long companyId, String sourceId, LocalDate periodEnd, int factCount,
                             Instant recordedAt) {
}
