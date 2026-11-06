package com.analyseparts.ingestion.euronext;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.company.Icb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Complète les sociétés avec leur fiche Euronext : secteur ICB et nombre d'actions admises.
 * Ces données changent rarement : chaque fiche est relue au plus une fois par mois.
 */
@Service
public class FactsheetEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(FactsheetEnrichmentService.class);
    static final Duration REFRESH_AFTER = Duration.ofDays(30);

    private final EuronextFactsheetClient client;
    private final CompanyRepository companies;
    private final Duration pause;

    public FactsheetEnrichmentService(EuronextFactsheetClient client, CompanyRepository companies,
                                      @Value("${analyse-parts.euronext.factsheet-pause:1s}") Duration pause) {
        this.client = client;
        this.companies = companies;
        this.pause = pause;
    }

    public record EnrichmentResult(int checked, int withSector, int withShares, int errors) {
    }

    /** Tous les jours à 4 h : ne traite que les fiches jamais lues ou lues il y a plus d'un mois. */
    @Scheduled(cron = "${analyse-parts.euronext.factsheet-cron:0 0 4 * * *}")
    void scheduled() {
        enrichDue();
    }

    public EnrichmentResult enrichDue() {
        List<Company> due = companies.findByFactsheetCheckedAtIsNullOrFactsheetCheckedAtBeforeOrderByName(
                Instant.now().minus(REFRESH_AFTER));
        log.info("Fiches Euronext : {} sociétés à lire", due.size());
        int sector = 0, shares = 0, errors = 0;
        for (Company company : due) {
            try {
                Icb icb = EuronextFactsheetParser.icb(client.block(company.getIsin(), company.getMic(), "fs_icb_block"))
                        .orElse(null);
                Long admitted = EuronextFactsheetParser.admittedShares(
                        client.block(company.getIsin(), company.getMic(), "fs_tradinginfo_block")).orElse(null);
                company.applyFactsheet(icb, admitted, Instant.now());
                companies.save(company);
                if (icb != null) sector++;
                if (admitted != null) shares++;
            } catch (RestClientException e) {
                log.warn("Fiche Euronext {} : {}", company.getName(), e.getMessage());
                errors++;
            }
            pause();
        }
        EnrichmentResult result = new EnrichmentResult(due.size(), sector, shares, errors);
        log.info("Fiches Euronext : {}", result);
        return result;
    }

    private void pause() {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
