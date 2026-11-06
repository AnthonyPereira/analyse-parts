package com.analyseparts.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un point de cours : clôture d'un jour, ou cours à un instant de la séance. */
public record PricePoint(Instant time, BigDecimal price, Long volume) {

    /** Clôture quotidienne. */
    public record Close(LocalDate day, BigDecimal close, Long volume) {
    }
}
