package com.analyseparts.scoring.internal;

import com.analyseparts.financials.FilingRecorded;
import com.analyseparts.scoring.ScoreHistoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consommateur du topic {@code filings}, groupe {@value #GROUP} : à chaque rapport enregistré,
 * recalcule les notes de la société et les ajoute à leur historique.
 * <p>
 * {@code auto.offset.reset=earliest} : la toute première fois que ce groupe lit le topic, il part
 * du <b>début</b> et traite tous les rapports déjà publiés. C'est l'avantage d'un topic qu'on garde :
 * un nouveau traitement rattrape le passé tout seul.
 * <p>
 * Le message peut arriver deux fois (outbox = « au moins une fois ») : sans conséquence, l'écriture
 * est idempotente.
 */
@Component
class FilingListener {

    static final String GROUP = "score-history";

    private final ScoreHistoryService history;
    private final JsonMapper json;

    FilingListener(ScoreHistoryService history, JsonMapper json) {
        this.history = history;
        this.json = json;
    }

    @KafkaListener(topics = FilingRecorded.TOPIC, groupId = GROUP, properties = "auto.offset.reset=earliest")
    void onFiling(String message) {
        history.recordFiling(json.readValue(message, FilingRecorded.class));
    }
}
