package com.analyseparts.scoring;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.scoring.core.Freshness;
import com.analyseparts.scoring.core.Profile;
import com.analyseparts.scoring.core.RatioCalculator;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioFamily;
import com.analyseparts.scoring.core.ScoreCalculator;
import com.analyseparts.scoring.core.ScoreCalculator.Score;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Couche Spring autour du calcul : va chercher les données, appelle le cœur en Java pur.
 * <p>
 * Le score est recalculé à chaque demande (quelques millisecondes par société) : pas de
 * score stocké à tenir à jour quand un rapport arrive ou qu'un barème change.
 */
@Service
public class ScoringService {

    private final CompanyRepository companies;
    private final FinancialsService financials;
    private final Clock clock;

    @Autowired
    public ScoringService(CompanyRepository companies, FinancialsService financials) {
        this(companies, financials, Clock.systemDefaultZone());
    }

    ScoringService(CompanyRepository companies, FinancialsService financials, Clock clock) {
        this.companies = companies;
        this.financials = financials;
        this.clock = clock;
    }

    /** Le score d'une société, avec toutes ses contributions. */
    public record CompanyScore(String isin, String name, Profile profile, Score score) {
    }

    /** Une ligne du classement. */
    public record RankingEntry(String isin, String name, String ticker, Integer score, double coverage,
                               LocalDate fiscalYearEnd, Freshness freshness, Map<RatioFamily, Integer> familyScore) {
    }

    public Optional<CompanyScore> score(String isin, Profile profile) {
        return companies.findByIsin(isin)
                .map(c -> new CompanyScore(c.getIsin(), c.getName(), profile, score(c, profile)));
    }

    public Optional<RatioSet> ratios(String isin) {
        return companies.findByIsin(isin).map(this::ratios);
    }

    /** Les sociétés notées, de la meilleure à la moins bonne ; celles sans score sont à la fin. */
    public List<RankingEntry> ranking(Profile profile) {
        return companies.findAll().stream()
                .filter(c -> c.getId() != null && financials.hasFilings(c.getId()))
                .map(c -> {
                    Score s = score(c, profile);
                    return new RankingEntry(c.getIsin(), c.getName(), c.getTicker(), s.score(), s.coverage(),
                            s.fiscalYearEnd(), s.freshness(), s.familyScore());
                })
                .sorted(Comparator.comparing(RankingEntry::score, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RankingEntry::name))
                .toList();
    }

    private Score score(Company company, Profile profile) {
        return ScoreCalculator.score(ratios(company), profile.weights(), LocalDate.now(clock));
    }

    private RatioSet ratios(Company company) {
        return RatioCalculator.compute(financials.history(company.getId()));
    }
}
