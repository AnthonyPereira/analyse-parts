package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Icb;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Le secteur ICB d'une société hors Euronext, déduit de son secteur chez Yahoo (ADR 0020).
 * <p>
 * Le barème des notes (ADR 0004) compare chaque société aux autres de son super-secteur ICB, et les
 * banques et assurances ont leurs propres poids : il faut donc un secteur ICB. Les fiches Euronext le
 * donnent pour Euronext seulement. La table {@code yahoo-icb.csv} a été construite à partir des
 * sociétés d'Euronext, qui ont les deux : pour chaque sous-secteur Yahoo, le sous-secteur ICB le plus
 * fréquent ; une ligne « * » par secteur Yahoo sert quand le sous-secteur est inconnu.
 */
@Component
public class YahooSectorMapping {

    static final String RESOURCE = "yahoo-icb.csv";

    private final Map<String, Icb> byIndustry = new HashMap<>();
    private final Map<String, Icb> bySector = new HashMap<>();

    public YahooSectorMapping() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                // secteur Yahoo;sous-secteur Yahoo;code ICB;industrie;super-secteur;secteur;sous-secteur
                String[] f = line.split(";", -1);
                Icb icb = new Icb(f[2], f[3], f[4], f[5], f[6]);
                if ("*".equals(f[1])) {
                    bySector.put(f[0], icb);
                } else {
                    byIndustry.put(f[1], icb);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Table " + RESOURCE + " illisible", e);
        }
    }

    /** Le secteur ICB le plus probable ; vide si Yahoo ne donne pas de secteur connu. */
    public Optional<Icb> findIcb(String yahooSector, String yahooIndustry) {
        Icb icb = yahooIndustry == null ? null : byIndustry.get(yahooIndustry);
        if (icb == null && yahooSector != null) {
            icb = bySector.get(yahooSector);
        }
        return Optional.ofNullable(icb);
    }
}
