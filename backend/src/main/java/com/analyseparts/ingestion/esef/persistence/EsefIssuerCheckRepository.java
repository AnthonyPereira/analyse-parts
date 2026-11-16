package com.analyseparts.ingestion.esef.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Les émetteurs ESEF déjà examinés (table {@code esef_issuer_check}) : un émetteur sans action
 * n'est pas recherché de nouveau chaque semaine.
 */
@Repository
public class EsefIssuerCheckRepository {

    private final JdbcTemplate jdbc;

    public EsefIssuerCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Les LEI déjà examinés, sauf ceux dont l'examen a échoué (ils seront réessayés). */
    public List<String> findCheckedLeis() {
        return jdbc.queryForList("SELECT lei FROM esef_issuer_check WHERE outcome <> 'ERROR'", String.class);
    }

    public void record(String lei, String country, Instant checkedAt, String outcome, String isin) {
        jdbc.update("""
                INSERT INTO esef_issuer_check (lei, country, checked_at, outcome, isin) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (lei) DO UPDATE SET checked_at = EXCLUDED.checked_at, outcome = EXCLUDED.outcome,
                                                isin = EXCLUDED.isin
                """, lei, country, Timestamp.from(checkedAt), outcome, isin);
    }
}
