package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.AnalysisService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Le lecteur local (profil {@code reader}, ADR 0019) : le PC qui a le modèle de langage se branche
 * sur la base en ligne, cherche les sociétés dont le dernier rapport n'est pas lu, les lit et
 * enregistre les lectures. Le serveur en ligne, lui, continue de collecter les rapports.
 * <p>
 * Un tour toutes les {@code analyse-parts.reader.poll-minutes} (30 par défaut), seulement quand la
 * file est vide : une requête pour trouver le travail, puis des heures de lecture sans toucher la base.
 * Les tâches planifiées sont coupées dans ce mode : la boucle a son propre fil.
 */
@Component
@ConditionalOnProperty(name = "analyse-parts.reader.enabled", havingValue = "true")
class ReportReaderLoop {

    private static final Logger log = LoggerFactory.getLogger(ReportReaderLoop.class);

    private final AnalysisService analyses;
    private final long pollMinutes;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "lecteur-local"));

    ReportReaderLoop(AnalysisService analyses, @Value("${analyse-parts.reader.poll-minutes:30}") long pollMinutes) {
        this.analyses = analyses;
        this.pollMinutes = pollMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        log.info("Lecteur local : recherche des rapports à lire toutes les {} min", pollMinutes);
        timer.scheduleWithFixedDelay(this::poll, 0, pollMinutes, TimeUnit.MINUTES);
    }

    void poll() {
        try {
            if (analyses.isIdle()) {
                int queued = analyses.requestMissing();
                if (queued == 0) {
                    log.info("Lecteur local : rien à lire, prochain tour dans {} min", pollMinutes);
                }
            }
        } catch (RuntimeException e) {
            // base injoignable (réseau, base endormie qui se réveille) : on réessaie au tour suivant
            log.warn("Lecteur local : recherche des rapports en échec : {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
