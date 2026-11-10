package com.analyseparts.ingestion.prices;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Taux de change de référence de la Banque centrale européenne, publiés chaque jour ouvré
 * vers 16 h. Format : {@code <Cube currency='USD' rate='1.0812'/>} = 1 euro vaut 1,0812 dollar.
 */
@Component
public class EcbFxClient {

    private static final Pattern DATE = Pattern.compile("time=['\"](\\d{4}-\\d{2}-\\d{2})['\"]");
    private static final Pattern RATE = Pattern.compile("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]");

    private final RestClient http;

    public EcbFxClient(RestClient.Builder builder,
                       @Value("${analyse-parts.ecb.base-url:https://www.ecb.europa.eu}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public record FxRates(LocalDate date, Map<String, BigDecimal> ratePerEur) {
    }

    public FxRates fetchDailyRates() {
        return parse(http.get().uri("/stats/eurofxref/eurofxref-daily.xml").retrieve().body(String.class));
    }

    static FxRates parse(String xml) {
        Matcher date = DATE.matcher(xml);
        if (!date.find()) {
            throw new IllegalStateException("Date absente du fichier de la BCE");
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        Matcher m = RATE.matcher(xml);
        while (m.find()) {
            rates.put(m.group(1), new BigDecimal(m.group(2)));
        }
        return new FxRates(LocalDate.parse(date.group(1)), rates);
    }
}
