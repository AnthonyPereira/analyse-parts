package com.analyseparts.ingestion.gleif;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;

/**
 * Ajoute le LEI aux sociétés qui n'en ont pas encore, via GLEIF.
 * Chaque société est enregistrée dès qu'elle est traitée : si le traitement s'arrête
 * en route, on reprend là où on en était.
 */
@Service
public class LeiEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(LeiEnrichmentService.class);

    private final GleifClient gleif;
    private final CompanyRepository companies;
    private final Duration pause;
    private final com.analyseparts.ingestion.SourceHealth health;

    public LeiEnrichmentService(GleifClient gleif, CompanyRepository companies,
                                @Value("${analyse-parts.gleif.pause:1100ms}") Duration pause,
                                com.analyseparts.ingestion.SourceHealth health) {
        this.health = health;
        this.gleif = gleif;
        this.companies = companies;
        this.pause = pause;
    }

    public record EnrichmentResult(int checked, int found, int notFound, int duplicates, int errors) {
    }

    /**
     * Au moins ce nombre de sociétés sans qu'aucun LEI soit trouvé : GLEIF répond, mais on ne sait
     * plus lire sa réponse (presque toutes les sociétés cotées ont un LEI, obligatoire pour publier).
     */
    static final int SUSPICIOUS_NOT_FOUND = 10;

    private void reportHealth(int checked, int found, int errors) {
        var source = com.analyseparts.ingestion.SourceHealth.Source.GLEIF;
        if (checked >= SUSPICIOUS_NOT_FOUND && found == 0 && errors < checked) {
            health.down(source, "GLEIF ne trouve aucun LEI pour " + checked
                    + " sociétés : son format de réponse a probablement changé");
        } else {
            health.report(source, checked, checked - errors,
                    errors > 0 ? errors + " requêtes GLEIF en échec (service indisponible ?)" : null);
        }
    }

    public EnrichmentResult enrichMissingLeis() {
        int checked = 0, found = 0, notFound = 0, duplicates = 0, errors = 0;
        for (Company company : companies.findByLeiCheckedAtIsNullOrderByName()) {
            checked++;
            try {
                var record = gleif.findByIsin(company.getIsin());
                Instant now = Instant.now();
                if (record.isEmpty()) {
                    company.markLeiChecked(now);
                    notFound++;
                } else if (companies.findByLei(record.get().lei()).isPresent()) {
                    // Deux titres pour la même entité (ex. 2 catégories d'actions) : on garde le premier.
                    log.warn("LEI {} déjà attribué, ignoré pour {} ({})",
                            record.get().lei(), company.getName(), company.getIsin());
                    company.markLeiChecked(now);
                    duplicates++;
                } else {
                    var r = record.get();
                    company.applyLei(r.lei(), r.legalName(), r.country(), now);
                    found++;
                }
                companies.save(company);
            } catch (RestClientException e) {
                // On ne marque pas la société : elle sera retentée au prochain passage.
                log.warn("GLEIF en erreur pour {} : {}", company.getIsin(), e.getMessage());
                errors++;
            }
            sleep();
        }
        reportHealth(checked, found, errors);
        log.info("Enrichissement LEI : {} traitées, {} trouvées, {} introuvables, {} doublons, {} erreurs",
                checked, found, notFound, duplicates, errors);
        return new EnrichmentResult(checked, found, notFound, duplicates, errors);
    }

    private void sleep() {
        if (pause.isZero()) {
            return;
        }
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
