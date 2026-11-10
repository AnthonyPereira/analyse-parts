package com.analyseparts.financials.internal;

import com.analyseparts.financials.MetricExtractor.SourcedFact;
import com.analyseparts.financials.CompanySize;
import com.analyseparts.financials.ReportedFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accès JDBC direct aux faits : un rapport en contient plusieurs centaines, un insert en lot
 * est bien plus rapide qu'une entité JPA par fait.
 */
@Repository
public class ReportedFactJdbcRepository {

    private final JdbcTemplate jdbc;

    public ReportedFactJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(long filingId, List<ReportedFact> facts) {
        jdbc.batchUpdate("""
                INSERT INTO reported_fact (filing_id, concept, period_start, period_end, unit, value, decimals)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, facts, 500, (ps, f) -> {
            ps.setLong(1, filingId);
            ps.setString(2, f.concept());
            if (f.periodStart() == null) {
                ps.setNull(3, Types.DATE);
            } else {
                ps.setDate(3, Date.valueOf(f.periodStart()));
            }
            ps.setDate(4, Date.valueOf(f.periodEnd()));
            ps.setString(5, f.unit());
            ps.setBigDecimal(6, f.value());
            if (f.decimals() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, f.decimals());
            }
        });
    }

    /** Chiffre d'affaires (à défaut 5 % du total du bilan) du dernier rapport de chaque société, en une requête. */
    public Map<Long, CompanySize> findLatestSizes() {
        Map<Long, CompanySize> sizes = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (fi.company_id) fi.company_id,
                       f.value * CASE WHEN f.concept = 'ifrs-full:Assets' THEN 0.05 ELSE 1 END, f.unit
                FROM filing fi
                JOIN reported_fact f ON f.filing_id = fi.id AND f.period_end = fi.period_end
                WHERE f.concept IN ('ifrs-full:Revenue', 'ifrs-full:Assets')
                  AND f.unit LIKE 'iso4217:%'
                  AND fi.period_end = (SELECT max(f2.period_end) FROM filing f2 WHERE f2.company_id = fi.company_id)
                ORDER BY fi.company_id, (f.concept = 'ifrs-full:Revenue') DESC, f.value DESC
                """, rs -> {
            sizes.put(rs.getLong(1), new CompanySize(rs.getBigDecimal(2),
                    rs.getString(3).substring("iso4217:".length())));
        });
        return sizes;
    }

    public List<SourcedFact> findByCompany(long companyId) {
        return jdbc.query("""
                SELECT f.concept, f.period_start, f.period_end, f.unit, f.value, f.decimals,
                       fi.source_id, fi.period_end AS filing_period_end
                FROM reported_fact f
                JOIN filing fi ON fi.id = f.filing_id
                WHERE fi.company_id = ?
                """, (rs, _) -> {
            Date start = rs.getDate("period_start");
            ReportedFact fact = new ReportedFact(
                    rs.getString("concept"),
                    start == null ? null : start.toLocalDate(),
                    rs.getDate("period_end").toLocalDate(),
                    rs.getString("unit"),
                    rs.getBigDecimal("value"),
                    rs.getObject("decimals", Integer.class));
            return new SourcedFact(fact, rs.getString("source_id"), rs.getDate("filing_period_end").toLocalDate());
        }, companyId);
    }
}
