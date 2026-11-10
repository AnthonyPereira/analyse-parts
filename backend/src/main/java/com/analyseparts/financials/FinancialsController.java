package com.analyseparts.financials;

import com.analyseparts.company.CompanyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Lecture des indicateurs d'une société. Ex. : GET /api/companies/FR0000120271/financials */
@RestController
public class FinancialsController {

    private final CompanyRepository companies;
    private final FinancialsService financials;

    public FinancialsController(CompanyRepository companies, FinancialsService financials) {
        this.companies = companies;
        this.financials = financials;
    }

    @GetMapping("/api/companies/{isin}/financials")
    public ResponseEntity<FinancialHistory> getHistory(@PathVariable String isin) {
        return companies.findByIsin(isin)
                .map(c -> ResponseEntity.ok(financials.getHistory(c.getId())))
                .orElse(ResponseEntity.notFound().build());
    }
}
