package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.HalfYearReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Accès à la table {@code half_year_report} (migration V15). */
@Repository
public class HalfYearRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public HalfYearRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Enregistre un rapport ; relire le même semestre remplace la lecture précédente. */
    public void save(long companyId, HalfYearReport r) {
        jdbc.update("""
                INSERT INTO half_year_report (company_id, period_end, published_on, title, source_url, model, created_at, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (company_id, period_end) DO UPDATE
                   SET published_on = EXCLUDED.published_on, title = EXCLUDED.title, source_url = EXCLUDED.source_url,
                       model = EXCLUDED.model, created_at = EXCLUDED.created_at, content = EXCLUDED.content
                """, companyId, java.sql.Date.valueOf(r.periodEnd()), java.sql.Date.valueOf(r.publishedOn()), r.title(),
                r.sourceUrl(), r.model(), Timestamp.from(r.createdAt()), json.writeValueAsString(r.lines()));
    }

    /** Le semestre le plus récent d'une société. */
    public Optional<HalfYearReport> findLatest(long companyId) {
        return jdbc.query("""
                SELECT period_end, published_on, title, source_url, model, created_at, content
                  FROM half_year_report WHERE company_id = ? ORDER BY period_end DESC LIMIT 1
                """, (rs, n) -> mapReport(rs), companyId).stream().findFirst();
    }

    /** Un rapport enregistré et la société à qui il appartient. */
    public record Stored(long companyId, HalfYearReport report) {
    }

    /** Tous les rapports enregistrés (une centaine de lignes). */
    public List<Stored> findAll() {
        return jdbc.query("""
                SELECT company_id, period_end, published_on, title, source_url, model, created_at, content
                  FROM half_year_report
                """, (rs, n) -> new Stored(rs.getLong("company_id"), mapReport(rs)));
    }

    /** Remplace les lignes d'un rapport, sans toucher au reste. */
    public void updateLines(long companyId, java.time.LocalDate periodEnd, List<HalfYearReport.Line> lines) {
        jdbc.update("UPDATE half_year_report SET content = ?::jsonb WHERE company_id = ? AND period_end = ?",
                json.writeValueAsString(lines), companyId, java.sql.Date.valueOf(periodEnd));
    }

    public void delete(long companyId, java.time.LocalDate periodEnd) {
        jdbc.update("DELETE FROM half_year_report WHERE company_id = ? AND period_end = ?",
                companyId, java.sql.Date.valueOf(periodEnd));
    }

    private HalfYearReport mapReport(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new HalfYearReport(rs.getDate("period_end").toLocalDate(),
                rs.getDate("published_on").toLocalDate(), rs.getString("title"), rs.getString("source_url"),
                rs.getString("model"), rs.getTimestamp("created_at").toInstant(),
                json.readValue(rs.getString("content"), new TypeReference<List<HalfYearReport.Line>>() {}));
    }
}
