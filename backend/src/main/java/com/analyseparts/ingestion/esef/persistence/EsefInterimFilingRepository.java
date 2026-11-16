package com.analyseparts.ingestion.esef.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Les rapports ESEF reconnus comme intermédiaires (table {@code esef_interim_filing}) : ils ne
 * sont plus téléchargés à chaque passage (ADR 0020).
 */
@Repository
public class EsefInterimFilingRepository {

    private final JdbcTemplate jdbc;

    public EsefInterimFilingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Parmi ces identifiants de rapport, ceux déjà reconnus comme intermédiaires. */
    public Set<String> findInterim(Collection<String> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT source_id FROM esef_interim_filing WHERE source_id = ANY(?)",
                String.class, (Object) sourceIds.toArray(String[]::new)));
    }

    public void markInterim(String sourceId, LocalDate periodEnd) {
        jdbc.update("INSERT INTO esef_interim_filing (source_id, period_end) VALUES (?, ?) ON CONFLICT DO NOTHING",
                sourceId, periodEnd);
    }
}
