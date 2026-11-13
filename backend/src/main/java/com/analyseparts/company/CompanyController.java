package com.analyseparts.company;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Identité des sociétés, pour la recherche et l'en-tête de la fiche. */
@RestController
public class CompanyController {

    private final CompanyRepository companies;

    public CompanyController(CompanyRepository companies) {
        this.companies = companies;
    }

    /** Une ligne de la liste de recherche. */
    public record CompanySummary(String isin, String name, String ticker, String supersector) {
    }

    /** L'identité complète d'une société. */
    public record CompanyView(String isin, String lei, String name, String legalName, String ticker,
                              String country, String market, String mic, Icb icb, Long sharesAdmitted) {
    }

    /** Toutes les sociétés, triées par nom (environ 2 200, une centaine de Ko : la liste entière tient dans une réponse). */
    @GetMapping("/api/companies")
    public List<CompanySummary> list() {
        return companies.findAll().stream()
                .sorted(Comparator.comparing(Company::getName))
                .map(c -> new CompanySummary(c.getIsin(), c.getName(), c.getTicker(),
                        c.getIcb().map(Icb::supersector).orElse(null)))
                .toList();
    }

    @GetMapping("/api/companies/{isin}")
    public ResponseEntity<CompanyView> get(@PathVariable String isin) {
        return ResponseEntity.of(companies.findByIsin(isin).map(c -> new CompanyView(c.getIsin(), c.getLei(),
                c.getName(), c.getLegalName(), c.getTicker(), c.getCountry(), c.getMarket(), c.getMic(),
                c.getIcb().orElse(null), c.getSharesAdmitted())));
    }
}
