package com.analyseparts.market.event;

import com.analyseparts.market.model.Quote;

import java.util.List;

/**
 * Publié par {@code MarketDataService} quand des cours changent ; écouté par {@code QuoteStream}
 * pour le direct, et par le module scoring pour recalculer les notes Value.
 */
public record QuotesUpdated(List<Quote> quotes) {
}
