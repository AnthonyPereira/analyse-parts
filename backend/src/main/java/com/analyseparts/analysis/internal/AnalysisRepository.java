package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.ReportAnalysis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Accès à la table {@code report_analysis} (migration V13). */
@Repository
public class AnalysisRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AnalysisRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Enregistre une lecture ; relire le même rapport remplace la précédente. */
    public void save(long companyId, ReportAnalysis a) {
        jdbc.update("""
                INSERT INTO report_analysis (company_id, source_id, period_end, model, created_at, duration_ms,
                                             passage_count, rejected, content, ranked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (company_id, source_id) DO UPDATE
                   SET period_end = EXCLUDED.period_end, model = EXCLUDED.model, created_at = EXCLUDED.created_at,
                       duration_ms = EXCLUDED.duration_ms, passage_count = EXCLUDED.passage_count,
                       rejected = EXCLUDED.rejected, content = EXCLUDED.content, ranked_at = EXCLUDED.ranked_at
                """, companyId, a.sourceId(), java.sql.Date.valueOf(a.periodEnd()), a.model(),
                Timestamp.from(a.createdAt()), a.durationMs(), a.passageCount(), a.rejected(),
                json.writeValueAsString(a.themes()), a.rankedAt() == null ? null : Timestamp.from(a.rankedAt()));
    }

    /** Les sociétés dont la dernière lecture a encore ses points dans l'ordre de lecture (ADR 0018). */
    public List<Long> findCompaniesWithUnrankedAnalysis() {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (company_id) company_id, ranked_at
                  FROM report_analysis ORDER BY company_id, period_end DESC, created_at DESC
                """).stream()
                .filter(row -> row.get("ranked_at") == null)
                .map(row -> ((Number) row.get("company_id")).longValue())
                .toList();
    }

    /** La lecture la plus récente d'une société. */
    public Optional<ReportAnalysis> findLatest(long companyId) {
        return jdbc.query("""
                SELECT source_id, period_end, model, created_at, duration_ms, passage_count, rejected, content, ranked_at
                  FROM report_analysis WHERE company_id = ? ORDER BY period_end DESC, created_at DESC LIMIT 1
                """, (rs, n) -> new ReportAnalysis(rs.getString("source_id"), rs.getDate("period_end").toLocalDate(),
                rs.getString("model"), rs.getTimestamp("created_at").toInstant(), rs.getLong("duration_ms"),
                rs.getInt("passage_count"), rs.getInt("rejected"),
                json.readValue(rs.getString("content"), new TypeReference<List<ReportAnalysis.Theme>>() {}),
                rs.getTimestamp("ranked_at") == null ? null : rs.getTimestamp("ranked_at").toInstant()),
                companyId).stream().findFirst();
    }

    /** Les rapports déjà lus (identifiants chez la source). */
    public Set<String> findAnalysedSourceIds() {
        return Set.copyOf(jdbc.queryForList("SELECT source_id FROM report_analysis", String.class));
    }
}
