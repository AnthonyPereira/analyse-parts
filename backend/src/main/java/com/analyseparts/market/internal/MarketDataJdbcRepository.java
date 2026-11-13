package com.analyseparts.market.internal;

import com.analyseparts.market.PricePoint;
import com.analyseparts.market.Quote;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /**
     * INSERT … ON CONFLICT : un seul aller-retour par lot. Le cours le plus récent gagne ; un
     * cours identique à celui en base ne modifie rien.
     *
     * @return les cours réellement enregistrés (nouveaux ou modifiés), à diffuser en direct
     */
    public List<Quote> upsertQuotes(Collection<Quote> quotes) {
        List<Quote> list = List.copyOf(quotes);
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO quote (company_id, price, currency, as_of, source, previous_close, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (company_id) DO UPDATE
                SET price = excluded.price, currency = excluded.currency, as_of = excluded.as_of,
                    source = excluded.source,
                    previous_close = COALESCE(excluded.previous_close, quote.previous_close),
                    updated_at = now()
                WHERE excluded.as_of > quote.as_of
                   OR (excluded.as_of = quote.as_of AND excluded.price <> quote.price)
                """, list, 200, (ps, q) -> {
            ps.setLong(1, q.companyId());
            ps.setBigDecimal(2, q.price());
            ps.setString(3, q.currency());
            ps.setTimestamp(4, Timestamp.from(q.asOf()));
            ps.setString(5, q.source());
            ps.setBigDecimal(6, q.previousClose());
        });
        List<Quote> changed = new ArrayList<>();
        int i = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                if (count > 0) {
                    changed.add(list.get(i));
                }
                i++;
            }
        }
        return changed;
    }

    /** Le dernier cours de chaque société, en une seule requête (pour le classement). */
    public Map<Long, Quote> findQuotes() {
        Map<Long, Quote> quotes = new HashMap<>();
        jdbc.query("SELECT company_id, price, currency, as_of, source, previous_close FROM quote",
                rs -> {
                    long id = rs.getLong("company_id");
                    quotes.put(id, new Quote(id, rs.getBigDecimal("price"), rs.getString("currency"),
                            rs.getTimestamp("as_of").toInstant(), rs.getString("source"),
                            rs.getBigDecimal("previous_close")));
                });
        return quotes;
    }

    public Optional<Quote> findQuote(long companyId) {
        return jdbc.query("""
                        SELECT company_id, price, currency, as_of, source, previous_close
                        FROM quote WHERE company_id = ?""",
                (rs, _) -> new Quote(rs.getLong("company_id"), rs.getBigDecimal("price"), rs.getString("currency"),
                        rs.getTimestamp("as_of").toInstant(), rs.getString("source"),
                        rs.getBigDecimal("previous_close")), companyId).stream().findFirst();
    }

    public int upsertCloses(long companyId, Collection<PricePoint.Close> closes) {
        jdbc.batchUpdate("""
                INSERT INTO price_history (company_id, day, close, volume) VALUES (?, ?, ?, ?)
                ON CONFLICT (company_id, day) DO UPDATE SET close = excluded.close, volume = excluded.volume
                """, List.copyOf(closes), 500, (ps, c) -> {
            ps.setLong(1, companyId);
            ps.setDate(2, Date.valueOf(c.day()));
            ps.setBigDecimal(3, c.close());
            if (c.volume() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, c.volume());
            }
        });
        return closes.size();
    }

    /** Avant {@code before}, supprime les clôtures qui ne sont ni la dernière de leur semaine, ni celle de leur mois. */
    public int thinCloses(LocalDate before) {
        return jdbc.update("""
                DELETE FROM price_history p
                 USING (SELECT company_id, day,
                               row_number() OVER (PARTITION BY company_id, date_trunc('week', day) ORDER BY day DESC) AS in_week,
                               row_number() OVER (PARTITION BY company_id, date_trunc('month', day) ORDER BY day DESC) AS in_month
                          FROM price_history WHERE day < ?) old
                 WHERE p.company_id = old.company_id AND p.day = old.day AND old.in_week > 1 AND old.in_month > 1
                """, Date.valueOf(before));
    }

    /** Clôtures depuis {@code from} inclus, de la plus ancienne à la plus récente. */
    public List<PricePoint.Close> findCloses(long companyId, LocalDate from) {
        return jdbc.query("""
                        SELECT day, close, volume FROM price_history
                        WHERE company_id = ? AND day >= ? ORDER BY day""",
                (rs, _) -> new PricePoint.Close(rs.getDate("day").toLocalDate(), rs.getBigDecimal("close"),
                        (Long) rs.getObject("volume")), companyId, Date.valueOf(from));
    }

    /** Dernière clôture connue au plus tard le {@code day} (les week-ends et jours fériés n'ont pas de cours). */
    public Optional<PricePoint.Close> findCloseOnOrBefore(long companyId, LocalDate day) {
        return jdbc.query("""
                        SELECT day, close, volume FROM price_history
                        WHERE company_id = ? AND day <= ? AND day > ? ORDER BY day DESC LIMIT 1""",
                (rs, _) -> new PricePoint.Close(rs.getDate("day").toLocalDate(), rs.getBigDecimal("close"),
                        (Long) rs.getObject("volume")),
                companyId, Date.valueOf(day), Date.valueOf(day.minusDays(10))).stream().findFirst();
    }

    /** Date de la dernière clôture enregistrée par société. */
    public Map<Long, LocalDate> findLatestCloseDays() {
        Map<Long, LocalDate> days = new HashMap<>();
        jdbc.query("SELECT company_id, max(day) AS day FROM price_history GROUP BY company_id",
                rs -> { days.put(rs.getLong("company_id"), rs.getDate("day").toLocalDate()); });
        return days;
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
