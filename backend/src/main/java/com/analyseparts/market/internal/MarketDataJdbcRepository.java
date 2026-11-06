package com.analyseparts.market.internal;

import com.analyseparts.market.Quote;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MarketDataJdbcRepository {

    private final JdbcTemplate jdbc;

    public MarketDataJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** INSERT … ON CONFLICT : un seul aller-retour par lot, et le cours le plus récent gagne. */
    public int upsertQuotes(Collection<Quote> quotes) {
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO quote (company_id, price, currency, as_of, source, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (company_id) DO UPDATE
                SET price = excluded.price, currency = excluded.currency, as_of = excluded.as_of,
                    source = excluded.source, updated_at = now()
                WHERE excluded.as_of >= quote.as_of
                """, List.copyOf(quotes), 200, (ps, q) -> {
            ps.setLong(1, q.companyId());
            ps.setBigDecimal(2, q.price());
            ps.setString(3, q.currency());
            ps.setTimestamp(4, Timestamp.from(q.asOf()));
            ps.setString(5, q.source());
        });
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).map(c -> Math.max(c, 0)).sum();
    }

    public Optional<Quote> findQuote(long companyId) {
        return jdbc.query("SELECT company_id, price, currency, as_of, source FROM quote WHERE company_id = ?",
                (rs, _) -> new Quote(rs.getLong("company_id"), rs.getBigDecimal("price"), rs.getString("currency"),
                        rs.getTimestamp("as_of").toInstant(), rs.getString("source")), companyId).stream().findFirst();
    }

    public void upsertFxRates(Map<String, BigDecimal> ratePerEur, LocalDate asOf) {
        jdbc.batchUpdate("""
                INSERT INTO fx_rate (currency, rate_per_eur, as_of) VALUES (?, ?, ?)
                ON CONFLICT (currency) DO UPDATE SET rate_per_eur = excluded.rate_per_eur, as_of = excluded.as_of
                """, List.copyOf(ratePerEur.entrySet()), 100, (ps, e) -> {
            ps.setString(1, e.getKey());
            ps.setBigDecimal(2, e.getValue());
            ps.setDate(3, Date.valueOf(asOf));
        });
    }

    public Map<String, BigDecimal> findFxRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        jdbc.query("SELECT currency, rate_per_eur FROM fx_rate",
                rs -> { rates.put(rs.getString("currency"), rs.getBigDecimal("rate_per_eur")); });
        return rates;
    }
}
