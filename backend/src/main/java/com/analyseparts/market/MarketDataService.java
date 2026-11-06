package com.analyseparts.market;

import com.analyseparts.market.internal.MarketDataJdbcRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** API publique du module : enregistrer et lire les cours et les taux de change. */
@Service
public class MarketDataService {

    private final MarketDataJdbcRepository repository;

    public MarketDataService(MarketDataJdbcRepository repository) {
        this.repository = repository;
    }

    /** Enregistre des cours ; un cours plus ancien que celui en base est ignoré. */
    public int recordQuotes(Collection<Quote> quotes) {
        return repository.upsertQuotes(quotes);
    }

    public Optional<Quote> latestQuote(long companyId) {
        return repository.findQuote(companyId);
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
