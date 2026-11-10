package com.analyseparts.scoring.internal;

import com.analyseparts.financials.FilingRecorded;
import com.analyseparts.scoring.ScoreHistoryService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * À chaque rapport enregistré, recalcule les notes de la société et les ajoute à leur historique.
 * <p>
 * {@code @ApplicationModuleListener} (Spring Modulith) = trois choses à la fois :
 * <ul>
 *   <li>appelé seulement <em>après le commit</em> du rapport (jamais pour un rapport annulé) ;</li>
 *   <li>en arrière-plan ({@code @Async}) : l'import n'attend pas le calcul ;</li>
 *   <li>dans sa propre transaction, et suivi par la table {@code event_publication} : si
 *       l'application s'arrête ou si le calcul échoue, l'appel est rejoué (au redémarrage, ou par
 *       {@code financials.internal.FilingOutboxRetry}).</li>
 * </ul>
 * Comme un appel peut être rejoué, l'écriture est idempotente ({@link ScoreHistoryRepository#upsert}).
 */
@Component
class FilingRecordedListener {

    private final ScoreHistoryService history;

    FilingRecordedListener(ScoreHistoryService history) {
        this.history = history;
    }

    @ApplicationModuleListener
    void handleFilingRecorded(FilingRecorded filing) {
        history.recordFiling(filing);
    }
}
