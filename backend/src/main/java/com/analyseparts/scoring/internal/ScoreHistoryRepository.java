package com.analyseparts.scoring.internal;

import com.analyseparts.scoring.core.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Accès à la table {@code score_history} (migration V12). */
@Repository
public class ScoreHistoryRepository {

    /** D'où vient une note enregistrée. */
    public enum Origin {
        /** Recalculée à l'arrivée d'un rapport (événement {@code FilingRecorded}). */
        FILING,
        /** Photo quotidienne après la clôture. */
        DAILY,
        /** Reconstituée à la clôture d'un exercice passé. */
        BACKFILL
    }

    /** Une note enregistrée. */
    public record Entry(Profile profile, LocalDate day, Origin origin, int score, LocalDate fiscalYearEnd,
                        String sourceId) {
    }

    private final JdbcTemplate jdbc;

    public ScoreHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Écrit les notes d'une société pour un jour et une origine. <b>Idempotent</b> : si la ligne
     * existe déjà (même société, profil, jour, origine), elle est remplacée, pas dupliquée. Un
     * événement rejoué laisse donc la table dans le même état qu'un seul traitement.
     * <p>
     * Les profils sans note (données insuffisantes) ne sont pas écrits.
     */
    public void upsert(long companyId, LocalDate day, Origin origin, Map<Profile, Integer> scores,
                       LocalDate fiscalYearEnd, String sourceId, Instant recordedAt) {
        List<Object[]> rows = new ArrayList<>();
        scores.forEach((profile, score) -> {
            if (score != null) {
                rows.add(new Object[]{companyId, profile.name(), Date.valueOf(day), origin.name(), score,
                        fiscalYearEnd == null ? null : Date.valueOf(fiscalYearEnd), sourceId, Timestamp.from(recordedAt)});
            }
        });
        jdbc.batchUpdate("""
                INSERT INTO score_history (company_id, profile, day, origin, score, fiscal_year_end, source_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (company_id, profile, day, origin) DO UPDATE
                   SET score = EXCLUDED.score, fiscal_year_end = EXCLUDED.fiscal_year_end,
                       source_id = EXCLUDED.source_id, recorded_at = EXCLUDED.recorded_at
                """, rows);
    }

    /** Toutes les notes enregistrées d'une société, par date. */
    public List<Entry> findByCompany(long companyId) {
        return jdbc.query("""
                SELECT profile, day, origin, score, fiscal_year_end, source_id
                  FROM score_history WHERE company_id = ? ORDER BY day, origin, profile
                """, (rs, _) -> new Entry(Profile.valueOf(rs.getString("profile")), rs.getDate("day").toLocalDate(),
                Origin.valueOf(rs.getString("origin")), rs.getInt("score"),
                rs.getDate("fiscal_year_end") == null ? null : rs.getDate("fiscal_year_end").toLocalDate(),
                rs.getString("source_id")), companyId);
    }

    public int count(long companyId, Origin origin) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM score_history WHERE company_id = ? AND origin = ?",
                Integer.class, companyId, origin.name());
        return n == null ? 0 : n;
    }

    /** Les sociétés qui ont déjà un historique reconstitué. */
    public List<Long> findCompaniesWithBackfill() {
        return jdbc.queryForList("SELECT DISTINCT company_id FROM score_history WHERE origin = 'BACKFILL'", Long.class);
    }
}
