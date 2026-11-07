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

    /** Publié quand des cours changent ; QuotePublisher le transmet au topic Kafka {@code quotes}. */
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
            events.publishEvent(new QuotesUpdated(changed));
        }
        return changed.size();
    }

    public Optional<Quote> latestQuote(long companyId) {
        return repository.findQuote(companyId);
    }

    public int recordCloses(long companyId, Collection<PricePoint.Close> closes) {
        return repository.upsertCloses(companyId, closes);
    }

    /** Clôtures quotidiennes depuis {@code from}, de la plus ancienne à la plus récente. */
    public List<PricePoint.Close> closes(long companyId, LocalDate from) {
        return repository.findCloses(companyId, from);
    }

    /** Le cours de clôture à une date (ou le dernier jour de bourse avant), ex. à la clôture d'un exercice. */
    public Optional<PricePoint.Close> closeOn(long companyId, LocalDate day) {
        return repository.findCloseOnOrBefore(companyId, day);
    }

    public Map<Long, LocalDate> latestCloseDays() {
        return repository.findLatestCloseDays();
    }

    /** La courbe de la dernière séance, avec un cache d'une minute. */
    public Optional<IntradaySource.Intraday> intraday(long companyId) {
        IntradaySource source = intradaySource.getIfAvailable();
        if (source == null) {
            return Optional.empty();
        }
        CachedIntraday cached = intradayCache.get(companyId);
        if (cached != null && cached.fetchedAt().plus(INTRADAY_CACHE).isAfter(Instant.now())) {
            return cached.value();
        }
        Optional<IntradaySource.Intraday> value = source.intraday(companyId);
        intradayCache.put(companyId, new CachedIntraday(Instant.now(), value));
        return value;
    }

    /** Taux de la BCE : combien d'unités de chaque devise vaut 1 euro. */
    public void recordFxRates(Map<String, BigDecimal> ratePerEur, LocalDate asOf) {
        repository.upsertFxRates(ratePerEur, asOf);
    }

    /**
     * Convertit un montant d'une devise à une autre via l'euro, ex. un bénéfice publié en
     * dollars pour le comparer à une capitalisation en euros. Vide si un taux manque.
     */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return Optional.of(amount);
        }
        Map<String, BigDecimal> rates = repository.findFxRates();
        BigDecimal fromRate = "EUR".equals(from) ? BigDecimal.ONE : rates.get(from);
        BigDecimal toRate = "EUR".equals(to) ? BigDecimal.ONE : rates.get(to);
        if (fromRate == null || toRate == null) {
            return Optional.empty();
        }
        return Optional.of(amount.divide(fromRate, MathContext.DECIMAL64).multiply(toRate, MathContext.DECIMAL64));
    }
}
