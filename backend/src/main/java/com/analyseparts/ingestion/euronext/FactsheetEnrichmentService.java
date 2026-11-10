package com.analyseparts.ingestion.euronext;

import com.analyseparts.ingestion.StockExchange;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.company.Icb;
import com.analyseparts.ingestion.SourceHealth;
import com.analyseparts.ingestion.SourceHealth.Source;
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
    private final SourceHealth health;

    public FactsheetEnrichmentService(EuronextFactsheetClient client, CompanyRepository companies,
                                      @Value("${analyse-parts.euronext.factsheet-pause:1s}") Duration pause,
                                      SourceHealth health) {
        this.client = client;
        this.companies = companies;
        this.pause = pause;
        this.health = health;
    }

    public record EnrichmentResult(int checked, int withSector, int withShares, int errors) {
    }

    /** Tous les jours à 4 h : ne traite que les fiches jamais lues ou lues il y a plus d'un mois. */
    @Scheduled(cron = "${analyse-parts.euronext.factsheet-cron:0 0 4 * * *}")
    void enrichOnSchedule() {
        enrichDue();
    }

    public EnrichmentResult enrichDue() {
        List<Company> due = companies.findByFactsheetCheckedAtIsNullOrFactsheetCheckedAtBeforeOrderByName(
                Instant.now().minus(REFRESH_AFTER));
        // seules les places d'Euronext ont une fiche ; les autres sociétés ont leur secteur par Yahoo
        due = due.stream().filter(c -> StockExchange.findByMic(c.getMic()).map(StockExchange::isEuronext).orElse(false))
                .toList();
        log.info("Fiches Euronext : {} sociétés à lire", due.size());
        int sector = 0, shares = 0, errors = 0;
        SourceHealth.Tally tally = new SourceHealth.Tally();
        for (Company listed : due) {
            try {
                Icb icb = EuronextFactsheetParser.parseIcb(client.fetchBlock(listed.getIsin(), listed.getMic(), "fs_icb_block"))
                        .orElse(null);
                Long admitted = EuronextFactsheetParser.parseAdmittedShares(
                        client.fetchBlock(listed.getIsin(), listed.getMic(), "fs_tradinginfo_block")).orElse(null);
                // relue juste avant d'enregistrer : la liste date du début, et la recherche des LEI a pu
                // modifier la société depuis ; l'enregistrer telle quelle effacerait ses changements
                Company company = companies.findById(listed.getId()).orElse(null);
                if (company == null) {
                    continue;
                }
                company.applyFactsheet(icb, admitted, Instant.now());
                companies.save(company);
                if (icb != null) sector++;
                if (admitted != null) shares++;
                if (icb == null && admitted == null) {
                    // page reçue, mais ni secteur ni nombre d'actions : la page a peut-être changé
                    tally.fail("fiche Euronext de " + company.getName()
                            + " lue, mais ni secteur ni nombre d'actions reconnus : format changé ?");
                } else {
                    tally.recordSuccess();
                }
            } catch (RestClientException e) {
                log.warn("Fiche Euronext {} : {}", listed.getName(), e.getMessage());
                tally.fail("fiche Euronext indisponible : " + e.getMessage());
                errors++;
            }
            pause();
        }
        health.report(Source.EURONEXT_FACTSHEET, tally);
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
