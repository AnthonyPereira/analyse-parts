package com.analyseparts.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un cours de bourse.
 *
 * @param asOf   heure du cours à la source
 * @param source {@code YAHOO} (en séance, ~15 min de différé) ou {@code EURONEXT_CLOSE} (clôture)
 */
public record Quote(long companyId, BigDecimal price, String currency, Instant asOf, String source) {
}
