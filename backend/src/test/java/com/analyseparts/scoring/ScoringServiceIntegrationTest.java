package com.analyseparts.scoring;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.company.Icb;
import com.analyseparts.financials.FilingData;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.financials.ReportedFact;
import com.analyseparts.market.MarketDataService;
import com.analyseparts.market.PricePoint;
import com.analyseparts.market.Quote;
import com.analyseparts.scoring.core.Profile;
import com.analyseparts.scoring.core.Ratio;
import com.analyseparts.scoring.core.ScoreCalculator.Contribution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(properties = {"analyse-parts.ingestion.auto-lei=false", "analyse-parts.amf.enabled=false"})
@Import(TestcontainersConfiguration.class)
class ScoringServiceIntegrationTest {

    @Autowired
    CompanyRepository companies;
    @Autowired
    FinancialsService financials;
    @Autowired
    MarketDataService market;
    @Autowired
    ScoringService scoring;

    @Test
    void valueScoreUsesTheLatestQuoteConvertedToTheReportingCurrency() {
        // Une société qui publie en dollars et cote en euros (comme TotalEnergies)
        Company company = new Company("FR0000000001", "PETROLE SA", "PET", "FR", "Euronext Paris");
        company.applyFactsheet(new Icb("60101010", "Energy", "Energy", "Oil", "Integrated Oil and Gas"),
                1_000_000L, Instant.now());
        company = companies.save(company);

        LocalDate end = LocalDate.now().withDayOfYear(1).minusDays(1); // dernier 31/12
        LocalDate start = end.withDayOfYear(1);
        List<ReportedFact> facts = new ArrayList<>();
        Map.of("ifrs-full:Revenue", "100000000", "ifrs-full:ProfitLossFromOperatingActivities", "15000000",
                "ifrs-full:ProfitLoss", "10000000", "ifrs-full:ProfitLossAttributableToOwnersOfParent", "10000000",
                "ifrs-full:CashFlowsFromUsedInOperatingActivities", "14000000",
                "ifrs-full:PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities", "6000000",
                "ifrs-full:DividendsPaidToEquityHoldersOfParentClassifiedAsFinancingActivities", "4000000")
                .forEach((concept, v) -> facts.add(new ReportedFact(concept, start, end, "iso4217:USD", new BigDecimal(v), -3)));
        facts.add(new ReportedFact("ifrs-full:BasicEarningsLossPerShare", start, end, "iso4217:USD/xbrli:shares",
                new BigDecimal("10"), 2));
        Map.of("ifrs-full:Equity", "80000000", "ifrs-full:EquityAttributableToOwnersOfParent", "80000000",
                "ifrs-full:Assets", "200000000", "ifrs-full:CurrentAssets", "60000000",
                "ifrs-full:CurrentLiabilities", "40000000", "ifrs-full:CashAndCashEquivalents", "10000000",
                "ifrs-full:LongtermBorrowings", "30000000")
                .forEach((concept, v) -> facts.add(new ReportedFact(concept, null, end, "iso4217:USD", new BigDecimal(v), -3)));
        financials.recordFiling(company.getId(), new FilingData("pet-" + end, end, "FR", "/pet.json", 0), facts);

        // 1 € = 1,25 $ : un cours de 80 € vaut 100 $
        market.recordFxRates(Map.of("USD", new BigDecimal("1.25")), LocalDate.now());
        market.recordQuotes(List.of(new Quote(company.getId(), new BigDecimal("80"), "EUR", Instant.now(), "YAHOO")));

        var result = scoring.score("FR0000000001", Profile.VALUE).orElseThrow();

        Contribution per = result.score().contributions().stream()
                .filter(c -> c.ratio() == Ratio.PER).findFirst().orElseThrow();
        assertThat(per.value()).isCloseTo(10.0, within(1e-6)); // 100 $ ÷ 10 $ de BPA
        assertThat(result.score().score()).isNotNull();
        // capitalisation 100 M$ : rendement du cash-flow libre = 8 M$ ÷ 100 M$
        assertThat(result.score().contributions()).anySatisfy(c -> {
            assertThat(c.ratio()).isEqualTo(Ratio.FCF_YIELD);
            assertThat(c.value()).isCloseTo(0.08, within(1e-6));
        });

        // Historique : à la clôture de l'exercice, le cours était de 40 € = 50 $ → PER 5
        market.recordCloses(company.getId(), List.of(new PricePoint.Close(end, new BigDecimal("40"), null)));
        var history = scoring.computeRatioHistory("FR0000000001").orElseThrow();
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().price()).isEqualByComparingTo("40");
        assertThat(history.getFirst().values().get(Ratio.PER)).isCloseTo(5.0, within(1e-6));
        assertThat(history.getFirst().values()).containsKey(Ratio.NET_MARGIN);

        // Mode avancé : un seul ratio suffit
        var custom = scoring.computeRanking(Map.of(Ratio.PER, 1));
        assertThat(custom).anySatisfy(e -> assertThat(e.isin()).isEqualTo("FR0000000001"));

        assertThat(scoring.buildUniverse()).anySatisfy(u -> {
            assertThat(u.isin()).isEqualTo("FR0000000001");
            assertThat(u.supersector()).isEqualTo("Energy");
            assertThat(u.ratios().get(Ratio.PER)).isCloseTo(10.0, within(1e-6));
            assertThat(u.scores()).containsKey(Profile.VALUE);
        });
    }
}
