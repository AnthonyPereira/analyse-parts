package com.analyseparts.financials.internal;

import com.analyseparts.financials.FilingRecorded;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Le filet de sécurité de l'outbox : toutes les 5 minutes, renvoie vers Kafka les rapports dont
 * l'envoi a échoué (Kafka arrêté…). Ils sont restés dans {@code event_publication} sans date de
 * fin ({@code completion_date} vide).
 * <p>
 * Au redémarrage, Spring Modulith fait la même chose
 * ({@code spring.modulith.events.republish-outstanding-events-on-restart}) ; ici, on n'attend pas
 * un redémarrage. L'âge minimal (2 minutes) évite de renvoyer un message en cours d'envoi.
 */
@Component
class FilingOutboxRetry {

    static final Duration MIN_AGE = Duration.ofMinutes(2);

    private final IncompleteEventPublications incomplete;

    FilingOutboxRetry(IncompleteEventPublications incomplete) {
        this.incomplete = incomplete;
    }

    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void resubmit() {
        incomplete.resubmitIncompletePublications(ResubmissionOptions.defaults()
                .withMinAge(MIN_AGE)
                .withFilter(publication -> publication.getEvent() instanceof FilingRecorded));
    }
}
