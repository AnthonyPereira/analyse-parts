package com.analyseparts.ingestion;

import com.analyseparts.ingestion.SourceHealth.State;
import com.analyseparts.ingestion.SourceHealth.Status;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L'indicateur {@code sources} de {@code /actuator/health} : l'endroit qu'interrogent les outils de
 * surveillance.
 * <p>
 * Une source en panne ne rend pas l'application « DOWN » : elle continue de servir les données déjà
 * collectées. On renvoie donc un statut à part, <b>{@code DEGRADED}</b> (déclaré dans
 * {@code application.properties} avec son rang et son code HTTP 200). Un orchestrateur qui
 * redémarre les applications « DOWN » ne redémarrera pas celle-ci pour une panne de Yahoo, ce qui
 * ne servirait à rien.
 */
@Component("sources")
class SourceHealthIndicator implements HealthIndicator {

    static final org.springframework.boot.health.contributor.Status DEGRADED =
            new org.springframework.boot.health.contributor.Status("DEGRADED");

    private final SourceHealth health;

    SourceHealthIndicator(SourceHealth health) {
        this.health = health;
    }

    @Override
    public Health health() {
        boolean problem = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (Status s : health.listAll()) {
            details.put(s.source().name(), s.state() == State.UP || s.state() == State.UNKNOWN
                    ? s.state().name()
                    : s.state().name() + " depuis " + s.problemSince() + " : " + s.problem());
            problem |= s.state() == State.DOWN || s.state() == State.DEGRADED;
        }
        return Health.status(problem ? DEGRADED : org.springframework.boot.health.contributor.Status.UP)
                .withDetails(details).build();
    }
}
