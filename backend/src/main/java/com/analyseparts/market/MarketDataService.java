package com.analyseparts.market;

import com.analyseparts.market.internal.MarketDataJdbcRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** API publique du module : enregistrer et lire les cours, leur historique et les taux de change. */
@Service
public class MarketDataService {

    /** La courbe du jour est relue au plus une fois par minute et par société. */
    private static final Duration INTRADAY_CACHE = Duration.ofMinutes(1);

    private final MarketDataJdbcRepository repository;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<IntradaySource> intradaySource;
    private final Map<Long, CachedIntraday> intradayCache = new ConcurrentHashMap<>();

    private record CachedIntraday(Instant fetchedAt, Optional<IntradaySource.Intraday> value) {
    }

    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(1);

    private record Snapshot(Instant loadedAt, Map<Long, Quote> quotes, Map<String, BigDecimal> fxRates) {
    }

    private volatile Snapshot snapshot;

    /** Publié quand des cours changent ; écouté par {@link QuoteStream} pour le direct. */
    public record QuotesUpdated(List<Quote> quotes) {
    }

    public MarketDataService(MarketDataJdbcRepository repository, ApplicationEventPublisher events,
                             ObjectProvider<IntradaySource> intradaySource) {
        this.repository = repository;
        this.events = events;
        this.intradaySource = intradaySource;
    }

    /** Enregistre des cours ; un cours plus ancien que celui en base, ou identique, est ignoré. */
    public int recordQuotes(Collection<Quote> quotes) {
        List<Quote> changed = repository.upsertQuotes(quotes);
        if (!changed.isEmpty()) {
            snapshot = null;
            events.publishEvent(new QuotesUpdated(changed));
        }
        return changed.size();
    }

    public Optional<Quote> findLatestQuote(long companyId) {
        return Optional.ofNullable(getSnapshot().quotes().get(companyId));
    }

    /**
     * Tous les derniers cours et taux de change, lus en deux requêtes et gardés une minute au plus
     * (vidés à chaque écriture). Le classement en lit un par société : une requête chacun faisait
     * environ 2 000 allers-retours vers la base à chaque visite (revue du 11 novembre 2026).
     */
    private Snapshot getSnapshot() {
        Snapshot current = snapshot;
        if (current == null || current.loadedAt().plus(SNAPSHOT_TTL).isBefore(Instant.now())) {
            current = new Snapshot(Instant.now(), Map.copyOf(repository.findQuotes()),
                    Map.copyOf(repository.findFxRates()));
            snapshot = current;
        }
        return current;
    }

    public int recordCloses(long companyId, Collection<PricePoint.Close> closes) {
        return repository.upsertCloses(companyId, closes);
    }

    /**
     * Au-delà d'un an, ne garde que la dernière clôture de chaque semaine et de chaque mois (celle-ci
     * donne le cours exact à la clôture des exercices) : 5 ans de cours prennent ainsi 2,5 fois moins
     * de place (ADR 0020). Renvoie le nombre de clôtures supprimées.
     */
    public int thinOldCloses() {
        return repository.thinCloses(LocalDate.now(java.time.ZoneId.of("Europe/Paris")).minusYears(1));
    }

    /** Clôtures quotidiennes depuis {@code from}, de la plus ancienne à la plus récente. */
    public List<PricePoint.Close> findCloses(long companyId, LocalDate from) {
        return repository.findCloses(companyId, from);
    }

    /** Le cours de clôture à une date (ou le dernier jour de bourse avant), ex. à la clôture d'un exercice. */
    public Optional<PricePoint.Close> findCloseOn(long companyId, LocalDate day) {
        return repository.findCloseOnOrBefore(companyId, day);
    }

    public Map<Long, LocalDate> findLatestCloseDays() {
        return repository.findLatestCloseDays();
    }

    /** La courbe de la dernière séance, avec un cache d'une minute. */
    public Optional<IntradaySource.Intraday> fetchIntraday(long companyId) {
        IntradaySource source = intradaySource.getIfAvailable();
        if (source == null) {
            return Optional.empty();
        }
        CachedIntraday cached = intradayCache.get(companyId);
        if (cached != null && cached.fetchedAt().plus(INTRADAY_CACHE).isAfter(Instant.now())) {
            return cached.value();
        }
        Optional<IntradaySource.Intraday> value = source.fetchIntraday(companyId);
        intradayCache.put(companyId, new CachedIntraday(Instant.now(), value));
        return value;
    }

    /** Taux de la BCE : combien d'unités de chaque devise vaut 1 euro. */
    public void recordFxRates(Map<String, BigDecimal> ratePerEur, LocalDate asOf) {
        repository.upsertFxRates(ratePerEur, asOf);
        snapshot = null;
    }

    /**
     * Convertit un montant d'une devise à une autre via l'euro, ex. un bénéfice publié en
     * dollars pour le comparer à une capitalisation en euros. Vide si un taux manque.
     */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return Optional.of(amount);
        }
        Map<String, BigDecimal> rates = getSnapshot().fxRates();
        BigDecimal fromRate = "EUR".equals(from) ? BigDecimal.ONE : rates.get(from);
        BigDecimal toRate = "EUR".equals(to) ? BigDecimal.ONE : rates.get(to);
        if (fromRate == null || toRate == null) {
            return Optional.empty();
        }
        return Optional.of(amount.divide(fromRate, MathContext.DECIMAL64).multiply(toRate, MathContext.DECIMAL64));
    }
}
