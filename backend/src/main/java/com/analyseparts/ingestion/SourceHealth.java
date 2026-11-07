package com.analyseparts.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * L'état de chaque source externe (Yahoo, BCE, AMF, Euronext…), tenu à jour après chaque collecte.
 * <p>
 * Pourquoi : ces sources ne préviennent pas quand elles changent leur format. Sans contrôle, un
 * champ renommé chez Yahoo donne… zéro cours, sans aucune erreur, et l'application affiche des cours
 * figés. Chaque collecte rapporte donc ce qu'elle a <b>tenté</b> et ce qui a <b>réussi</b> :
 * <ul>
 *   <li>rien n'a réussi → {@link State#DOWN} (source injoignable, ou format changé) ;</li>
 *   <li>moins que le taux attendu de la source → {@link State#DEGRADED} ;</li>
 *   <li>sinon → {@link State#UP}.</li>
 * </ul>
 * Un changement d'état est écrit <b>une seule fois</b> dans les logs (ERROR à la panne, INFO au
 * rétablissement), pas à chaque minute. L'état est visible sur {@code /actuator/health}
 * (indicateur {@code sources}) et dans un bandeau du site ({@code GET /api/sources}).
 * <p>
 * Gardé en mémoire : après un redémarrage, chaque source est « inconnue » jusqu'à sa prochaine collecte.
 */
@Component
public class SourceHealth {

    private static final Logger log = LoggerFactory.getLogger(SourceHealth.class);

    /**
     * Les sources, avec leur taux de réussite minimal. Quelques échecs sont normaux pour les sources
     * interrogées société par société (titre suspendu, rapport absent) : 3 % des symboles n'ont pas
     * de cours chez Yahoo, par exemple.
     */
    public enum Source {
        YAHOO_QUOTES("Cours en direct (Yahoo)", 0.8),
        YAHOO_HISTORY("Historique des cours (Yahoo)", 0.8),
        ECB_FX("Taux de change (BCE)", 1.0),
        AMF("Rapports annuels (AMF)", 0.8),
        FILINGS_XBRL("Rapports annuels (filings.xbrl.org)", 0.8),
        EURONEXT_LIST("Liste des actions (Euronext)", 1.0),
        EURONEXT_FACTSHEET("Fiches société (Euronext)", 0.8),
        GLEIF("Identifiants LEI (GLEIF)", 0.8);

        private final String label;
        private final double minSuccessRate;

        Source(String label, double minSuccessRate) {
            this.label = label;
            this.minSuccessRate = minSuccessRate;
        }

        public String label() {
            return label;
        }
    }

    public enum State {
        /** Pas encore de collecte depuis le démarrage. */
        UNKNOWN,
        UP,
        /** Une partie seulement a fonctionné. */
        DEGRADED,
        /** Rien n'a fonctionné. */
        DOWN
    }

    /**
     * @param problem      ce qui ne va pas (null si tout va bien)
     * @param problemSince début du problème en cours (il ne change pas tant que la source reste en échec)
     */
    public record Status(Source source, String label, State state, String detail, String problem,
                         Instant lastCheck, Instant lastSuccess, Instant problemSince) {
    }

    /**
     * Les résultats d'une collecte : chaque essai réussi ({@link #ok}) ou raté ({@link #fail}).
     * On garde le premier message d'échec, souvent le plus parlant.
     */
    public static final class Tally {
        private int attempted;
        private int succeeded;
        private String firstProblem;

        public void ok() {
            attempted++;
            succeeded++;
        }

        public void fail(String problem) {
            attempted++;
            if (firstProblem == null) {
                firstProblem = problem;
            }
        }

        public int attempted() {
            return attempted;
        }

        public int succeeded() {
            return succeeded;
        }
    }

    private final Clock clock;
    private final Map<Source, Status> statuses = new EnumMap<>(Source.class);

    public SourceHealth() {
        this(Clock.systemUTC());
    }

    SourceHealth(Clock clock) {
        this.clock = clock;
        for (Source s : Source.values()) {
            statuses.put(s, new Status(s, s.label, State.UNKNOWN, null, null, null, null, null));
        }
    }

    public void report(Source source, Tally tally) {
        report(source, tally.attempted, tally.succeeded, tally.firstProblem);
    }

    /**
     * Le bilan d'une collecte.
     *
     * @param attempted nombre d'essais (sociétés, requêtes, fichiers) ; 0 : rien à faire, état inchangé
     * @param succeeded essais réussis
     * @param problem   la cause principale des échecs, en français (peut être null)
     */
    public synchronized void report(Source source, int attempted, int succeeded, String problem) {
        if (attempted <= 0) {
            return;
        }
        Instant now = Instant.now(clock);
        Status before = statuses.get(source);
        String detail = succeeded + " sur " + attempted;
        State state;
        if (succeeded == 0) {
            state = State.DOWN;
        } else if ((double) succeeded / attempted < source.minSuccessRate) {
            state = State.DEGRADED;
        } else {
            state = State.UP;
        }
        String shownProblem = state == State.UP ? null
                : (problem != null ? problem : "seulement " + detail + " ont abouti");
        Instant since = state == State.UP ? null
                : (before.state() == State.DOWN || before.state() == State.DEGRADED) ? before.problemSince() : now;
        Instant lastSuccess = succeeded > 0 ? now : before.lastSuccess();
        statuses.put(source, new Status(source, source.label, state, detail, shownProblem, now, lastSuccess, since));

        if (state != before.state()) {
            switch (state) {
                case DOWN -> log.error("Source {} en panne ({}) : {}", source.label, detail, shownProblem);
                case DEGRADED -> log.warn("Source {} dégradée ({}) : {}", source.label, detail, shownProblem);
                case UP -> {
                    if (before.state() != State.UNKNOWN) {
                        log.info("Source {} rétablie ({})", source.label, detail);
                    }
                }
                default -> { }
            }
        }
    }

    /** Un échec complet, sans compte détaillé (exception avant tout essai, par exemple). */
    public void down(Source source, String problem) {
        report(source, 1, 0, problem);
    }

    public synchronized List<Status> all() {
        return Arrays.stream(Source.values()).map(statuses::get).toList();
    }

    public synchronized Status of(Source source) {
        return statuses.get(source);
    }
}
