package com.analyseparts.market;

import com.analyseparts.market.internal.MarketDataJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le cache des cours ne doit pas garder une lecture faite pendant une écriture. Défaut trouvé par
 * l'intégration continue le 13 novembre 2026 : un calcul en arrière-plan lisait tous les cours, le
 * test enregistrait un cours et vidait le cache, puis le calcul y rangeait sa lecture, sans ce cours.
 */
class MarketDataSnapshotTest {

    @Test
    @SuppressWarnings("unchecked")
    void aReadOverlappingAWriteIsNotKept() {
        MarketDataJdbcRepository repository = mock(MarketDataJdbcRepository.class);
        Quote quote = new Quote(1L, new BigDecimal("80"), "EUR", Instant.now(), "YAHOO");
        when(repository.upsertQuotes(any())).thenReturn(List.of(quote));
        when(repository.findFxRates()).thenReturn(Map.of());
        MarketDataService market = new MarketDataService(repository, mock(ApplicationEventPublisher.class),
                mock(ObjectProvider.class));

        AtomicInteger reads = new AtomicInteger();
        when(repository.findQuotes()).thenAnswer(_ -> {
            if (reads.incrementAndGet() == 1) {
                // l'écriture arrive pendant la première lecture, qui ne la voit pas
                market.recordQuotes(List.of(quote));
                return Map.of();
            }
            return Map.of(1L, quote);
        });

        assertThat(market.findLatestQuote(1L)).isEmpty(); // la lecture en cours, d'avant l'écriture
        assertThat(market.findLatestQuote(1L)).contains(quote); // relue : la copie périmée n'est pas gardée
        assertThat(market.findLatestQuote(1L)).contains(quote);
        assertThat(reads).hasValue(2); // puis la copie sert bien de cache
    }
}
