package com.analyseparts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Vide la table {@code event_publication} des événements traités depuis plus d'une semaine.
 * <p>
 * Spring Modulith y garde chaque événement, traité ou non : les non traités servent à rejouer un
 * listener qui a échoué ; les traités ne servent plus à rien, mais restaient (4 752 lignes, 2,5 Mo,
 * à la revue du 11 novembre 2026). Une semaine laisse le temps d'enquêter sur un problème récent.
 */
@Component
class EventPublicationCleanup {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationCleanup.class);

    static final Duration KEEP = Duration.ofDays(7);

    private final CompletedEventPublications completed;

    EventPublicationCleanup(CompletedEventPublications completed) {
        this.completed = completed;
    }

    /** Chaque jour de semaine à 7 h 50 (le serveur de démonstration dort la nuit). */
    @Scheduled(cron = "${analyse-parts.events.cleanup-cron:0 50 7 * * MON-FRI}", zone = "Europe/Paris")
    void deleteOldCompletedEvents() {
        completed.deletePublicationsOlderThan(KEEP);
        log.debug("Événements traités depuis plus de {} jours supprimés", KEEP.toDays());
    }
}
