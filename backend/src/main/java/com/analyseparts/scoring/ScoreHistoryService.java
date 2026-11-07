package com.analyseparts.scoring;

import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FilingRecorded;
import com.analyseparts.scoring.ScoringService.ProfileScores;
import com.analyseparts.scoring.core.Profile;
import com.analyseparts.scoring.internal.ScoreHistoryRepository;
import com.analyseparts.scoring.internal.ScoreHistoryRepository.Entry;
import com.analyseparts.scoring.internal.ScoreHistoryRepository.Origin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * L'<b>historique des notes</b> : des photos des 4 notes d'une société, prises à trois moments.
 * <ul>
 *   <li>{@link Origin#FILING} : à l'arrivée d'un rapport (événement {@code FilingRecorded},
 *       {@code internal.FilingRecordedListener}) ;</li>
 *   <li>{@link Origin#DAILY} : chaque soir de bourse, après la clôture (la note Value suit le cours) ;</li>
 *   <li>{@link Origin#BACKFILL} : une fois, pour chaque exercice passé, à sa date de clôture.</li>
 * </ul>
 * Toutes les écritures sont idempotentes (voir {@link ScoreHistoryRepository#upsert}).
 */
@Service
public class ScoreHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ScoreHistoryService.class);
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final ScoringService scoring;
    private final ScoreHistoryRepository repository;
    private final CompanyRepository companies;
    private final boolean backfillOnStartup;
    private final Clock clock = Clock.system(PARIS);

    public ScoreHistoryService(ScoringService scoring, ScoreHistoryRepository repository, CompanyRepository companies,
                               @Value("${analyse-parts.score-history.backfill-on-startup:true}") boolean backfillOnStartup) {
        this.scoring = scoring;
        this.repository = repository;
        this.companies = companies;
        this.backfillOnStartup = backfillOnStartup;
    }

    /**
     * Un rapport est arrivé : on photographie les notes, datées du jour de l'enregistrement du
     * rapport (lu dans l'événement, donc identique s'il est rejoué), et on complète
     * l'historique reconstitué avec le nouvel exercice.
     */
    public void recordFiling(FilingRecorded filing) {
        Optional<ProfileScores> scores = scoring.currentScores(filing.isin());
        if (scores.isEmpty()) {
            log.warn("Rapport {} : société {} inconnue, événement ignoré", filing.sourceId(), filing.isin());
            return;
        }
        ProfileScores s = scores.get();
        repository.upsert(s.companyId(), LocalDate.ofInstant(filing.recordedAt(), PARIS), Origin.FILING, s.scores(),
                s.fiscalYearEnd(), filing.sourceId(), filing.recordedAt());
        backfill(filing.isin());
        log.info("Historique des notes : rapport {} de {} → {}", filing.sourceId(), filing.isin(), s.scores());
    }

    /** Chaque soir de bourse, après l'enregistrement des cours de clôture (18 h 15). */
    @Scheduled(cron = "${analyse-parts.score-history.daily-cron:0 45 18 * * MON-FRI}", zone = "Europe/Paris")
    public int recordDaily() {
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);
        List<ProfileScores> all = scoring.allCurrentScores();
        all.forEach(s -> repository.upsert(s.companyId(), today, Origin.DAILY, s.scores(), s.fiscalYearEnd(), null, now));
        log.info("Historique des notes : photo du {} pour {} sociétés", today, all.size());
        return all.size();
    }

    /** Reconstitue les notes des exercices passés d'une société (une ligne par exercice). */
    public int backfill(String isin) {
        Instant now = Instant.now(clock);
        List<ProfileScores> past = scoring.pastScores(isin);
        past.forEach(s -> repository.upsert(s.companyId(), s.fiscalYearEnd(), Origin.BACKFILL, s.scores(),
                s.fiscalYearEnd(), null, now));
        return past.size();
    }

    /** Au démarrage, en arrière-plan : reconstitue l'historique des sociétés qui n'en ont pas encore. */
    @EventListener(ApplicationReadyEvent.class)
    void backfillMissing() {
        if (!backfillOnStartup) {
            return;
        }
        Thread.ofVirtual().name("historique-notes").start(() -> {
            Set<Long> done = new HashSet<>(repository.companiesWithBackfill());
            List<String> todo = scoring.scoredIsins().stream()
                    .filter(isin -> companies.findByIsin(isin).map(c -> !done.contains(c.getId())).orElse(false))
                    .toList();
            if (todo.isEmpty()) {
                return;
            }
            int years = 0;
            for (String isin : todo) {
                try {
                    years += backfill(isin);
                } catch (RuntimeException e) {
                    log.warn("Historique des notes de {} : {}", isin, e.getMessage());
                }
            }
            log.info("Historique des notes reconstitué : {} sociétés, {} exercices", todo.size(), years);
        });
    }

    /** Un point du graphique : les notes d'un jour (null : pas de note ce jour-là pour ce profil). */
    public record Point(LocalDate day, Origin origin, LocalDate fiscalYearEnd, String sourceId,
                        Map<Profile, Integer> scores) {
    }

    /**
     * L'historique d'une société, un point par jour. Si un jour a plusieurs origines (un rapport
     * arrivé un soir de photo quotidienne), on garde la plus précise : FILING, puis DAILY, puis BACKFILL.
     */
    public Optional<List<Point>> history(String isin) {
        return companies.findByIsin(isin).map(company -> {
            Map<LocalDate, Map<Origin, List<Entry>>> byDay = new LinkedHashMap<>();
            for (Entry e : repository.findByCompany(company.getId())) {
                byDay.computeIfAbsent(e.day(), _ -> new EnumMap<>(Origin.class))
                        .computeIfAbsent(e.origin(), _ -> new ArrayList<>()).add(e);
            }
            List<Point> points = new ArrayList<>();
            byDay.forEach((day, origins) -> {
                Map.Entry<Origin, List<Entry>> best = origins.entrySet().iterator().next(); // ordre de l'enum
                Map<Profile, Integer> scores = new EnumMap<>(Profile.class);
                best.getValue().forEach(e -> scores.put(e.profile(), e.score()));
                Entry first = best.getValue().getFirst();
                points.add(new Point(day, best.getKey(), first.fiscalYearEnd(), first.sourceId(), scores));
            });
            return points;
        });
    }
}
