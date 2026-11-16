package com.analyseparts.scoring.job;

import com.analyseparts.scoring.service.ScoreHistoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Les photos planifiées de l'historique des notes ({@link ScoreHistoryService}). */
@Component
class ScoreHistoryJob {

    private final ScoreHistoryService history;
    private final boolean backfillOnStartup;

    ScoreHistoryJob(ScoreHistoryService history,
                    @Value("${analyse-parts.score-history.backfill-on-startup:true}") boolean backfillOnStartup) {
        this.history = history;
        this.backfillOnStartup = backfillOnStartup;
    }

    /** Chaque soir de bourse, après l'enregistrement des cours de clôture (18 h 15). */
    @Scheduled(cron = "${analyse-parts.score-history.daily-cron:0 45 18 * * MON-FRI}", zone = "Europe/Paris")
    void recordDaily() {
        history.recordDaily();
    }

    /** Au démarrage, en arrière-plan : reconstitue l'historique des sociétés qui n'en ont pas encore. */
    @EventListener(ApplicationReadyEvent.class)
    void backfillMissing() {
        if (backfillOnStartup) {
            Thread.ofVirtual().name("historique-notes").start(history::backfillMissing);
        }
    }
}
