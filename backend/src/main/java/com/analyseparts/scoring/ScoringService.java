package com.analyseparts.scoring;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.company.Icb;
import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.market.MarketDataService;
import com.analyseparts.market.Quote;
import com.analyseparts.scoring.core.Freshness;
import com.analyseparts.scoring.core.Profile;
import com.analyseparts.scoring.core.Ratio;
import com.analyseparts.scoring.core.RatioCalculator;
import com.analyseparts.scoring.core.RatioCalculator.RatioSet;
import com.analyseparts.scoring.core.RatioFamily;
import com.analyseparts.scoring.core.ScoreCalculator;
import com.analyseparts.scoring.core.ScoreCalculator.Score;
import com.analyseparts.scoring.core.SectorCalibration;
import com.analyseparts.scoring.core.Valuation;
import com.analyseparts.scoring.internal.SectorCalibrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Couche Spring autour du calcul : va chercher les données, appelle le cœur en Java pur.
 * <p>
 * Le score est recalculé à chaque demande (quelques millisecondes par société) : pas de
 * score stocké à tenir à jour quand un rapport ou un cours arrive. Seule la calibration
 * sectorielle est enregistrée, pour rester stable entre deux calibrages.
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);
    /** Un cours plus ancien (suspension de cotation, symbole erroné…) n'est pas utilisé. */
    static final Duration MAX_QUOTE_AGE = Duration.ofDays(7);

    private final CompanyRepository companies;
    private final FinancialsService financials;
    private final MarketDataService market;
    private final SectorCalibrationRepository calibrations;
    private final Clock clock;
    private volatile SectorCalibration calibration;

    @Autowired
    public ScoringService(CompanyRepository companies, FinancialsService financials, MarketDataService market,
                          SectorCalibrationRepository calibrations) {
        this(companies, financials, market, calibrations, Clock.systemDefaultZone());
    }

    ScoringService(CompanyRepository companies, FinancialsService financials, MarketDataService market,
                   SectorCalibrationRepository calibrations, Clock clock) {
        this.companies = companies;
        this.financials = financials;
        this.market = market;
        this.calibrations = calibrations;
        this.clock = clock;
    }

    /** Le score d'une société, avec toutes ses contributions. */
    public record CompanyScore(String isin, String name, Icb icb, Profile profile, Score score) {
    }

    /** Une ligne du classement. */
    public record RankingEntry(String isin, String name, String ticker, String sector, Integer score, double coverage,
                               LocalDate fiscalYearEnd, Freshness freshness, Map<RatioFamily, Integer> familyScore) {
    }

    public Optional<CompanyScore> score(String isin, Profile profile) {
        return companies.findByIsin(isin)
                .map(c -> new CompanyScore(c.getIsin(), c.getName(), c.getIcb().orElse(null), profile, score(c, profile)));
    }

    public Optional<RatioSet> ratios(String isin) {
        return companies.findByIsin(isin).map(this::ratios);
    }

    /** Les sociétés notées, de la meilleure à la moins bonne ; celles sans score sont à la fin. */
    public List<RankingEntry> ranking(Profile profile) {
        return ranking(profile.weights());
    }

    /** Classement avec des poids choisis librement (mode avancé). */
    public List<RankingEntry> ranking(Map<Ratio, Integer> weights) {
        return scoredCompanies().stream()
                .map(c -> {
                    Score s = score(c, ratios(c), weights);
                    return new RankingEntry(c.getIsin(), c.getName(), c.getTicker(),
                            c.getIcb().map(Icb::supersector).orElse(null), s.score(), s.coverage(),
                            s.fiscalYearEnd(), s.freshness(), s.familyScore());
                })
                .sorted(Comparator.comparing(RankingEntry::score, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RankingEntry::name))
                .toList();
    }

    /** La calibration en vigueur (calculée au premier besoin si aucune n'est enregistrée). */
    public SectorCalibration calibration() {
        SectorCalibration current = calibration;
        if (current == null) {
            current = calibrations.load();
            if (current.calibratedAt().isEmpty()) {
                current = recalibrate();
            }
            calibration = current;
        }
        return current;
    }

    /**
     * Recalcule les médianes par secteur à partir des ratios actuels de toutes les sociétés,
     * puis les fige jusqu'au prochain calibrage.
     */
    public synchronized SectorCalibration recalibrate() {
        List<SectorCalibration.Sample> samples = scoredCompanies().stream()
                .map(c -> new SectorCalibration.Sample(c.getIcb().orElse(null), ratios(c)))
                .toList();
        SectorCalibration computed = SectorCalibration.compute(samples, Instant.now(clock));
        if (!computed.medians().isEmpty()) {
            calibrations.replace(computed);
        }
        log.info("Calibration sectorielle : {} médianes sur {} sociétés", computed.medians().size(), samples.size());
        calibration = computed;
        return computed;
    }

    private List<Company> scoredCompanies() {
        Set<Long> withFilings = financials.companiesWithFilings();
        return companies.findAll().stream()
                .filter(c -> c.getId() != null && withFilings.contains(c.getId()))
                .toList();
    }

    private Score score(Company company, Profile profile) {
        return score(company, ratios(company), profile.weights());
    }

    private Score score(Company company, RatioSet ratios, Map<Ratio, Integer> weights) {
        SectorCalibration cal = calibration();
        Icb icb = company.getIcb().orElse(null);
        return ScoreCalculator.score(ratios, weights, LocalDate.now(clock), ratio -> cal.adjustment(ratio, icb));
    }

    /**
     * Une société vue dans tout l'univers : ses ratios et ses 4 scores, pour les graphiques
     * de comparaison (nuages de points, distributions par secteur).
     */
    public record UniverseEntry(String isin, String name, String ticker, String supersectorCode, String supersector,
                                Freshness freshness, Map<Ratio, Double> ratios, Map<Profile, Integer> scores) {
    }

    /**
     * Au démarrage, calcule une fois l'univers en arrière-plan : les historiques des sociétés
     * entrent dans le cache, et la première page affichée n'attend pas.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        Thread.ofVirtual().name("prechauffage-scores").start(() -> {
            long start = System.nanoTime();
            int size = universe().size();
            log.info("Préchauffage : {} sociétés en {} ms", size, (System.nanoTime() - start) / 1_000_000);
        });
    }

    public List<UniverseEntry> universe() {
        return scoredCompanies().stream()
                .map(c -> {
                    RatioSet ratios = ratios(c);
                    Map<Ratio, Double> values = new EnumMap<>(Ratio.class);
                    ratios.values().forEach((r, v) -> values.put(r, v.value()));
                    Map<Profile, Integer> scores = new EnumMap<>(Profile.class);
                    for (Profile p : Profile.values()) {
                        scores.put(p, score(c, ratios, p.weights()).score());
                    }
                    Icb icb = c.getIcb().orElse(null);
                    return new UniverseEntry(c.getIsin(), c.getName(), c.getTicker(),
                            icb == null ? null : icb.supersectorCode(), icb == null ? null : icb.supersector(),
                            Freshness.of(ratios.fiscalYearEnd(), LocalDate.now(clock)), values, scores);
                })
                .sorted(Comparator.comparing(UniverseEntry::name))
                .toList();
    }

    /**
     * Les ratios d'un exercice, avec le cours de clôture de ce jour-là (pour la valorisation).
     *
     * @param price cours à la date de clôture de l'exercice (null si l'historique ne remonte pas si loin)
     */
    public record YearRatios(LocalDate fiscalYearEnd, BigDecimal price, Map<Ratio, Double> values) {
    }

    /**
     * Les ratios exercice par exercice, du plus ancien au plus récent. Pour chaque exercice, on
     * calcule comme si l'on était à sa clôture : mêmes formules, avec les seuls exercices connus
     * à cette date, et le cours de ce jour-là pour le PER, le rendement… Le nombre d'actions est
     * celui de l'époque (résultat net ÷ BPA), pas celui d'aujourd'hui.
     */
    public Optional<List<YearRatios>> ratioHistory(String isin) {
        return companies.findByIsin(isin).map(company -> {
            FinancialHistory history = financials.history(company.getId());
            String quoted = market.latestQuote(company.getId()).map(Quote::currency).orElse("EUR");
            String reporting = reportingCurrency(history).orElse(quoted);
            List<YearRatios> result = new ArrayList<>();
            for (int i = history.years().size() - 1; i >= 0; i--) { // du plus ancien au plus récent
                FinancialHistory upToYear = new FinancialHistory(history.years().subList(i, history.years().size()));
                LocalDate end = history.years().get(i).fiscalYearEnd();
                Valuation valuation = market.closeOn(company.getId(), end)
                        .flatMap(close -> market.convert(close.close(), quoted, reporting)
                                .map(price -> new Valuation(price, reporting, close.close(), quoted,
                                        close.day().atStartOfDay(clock.getZone()).toInstant(), "CLOSE", null)))
                        .orElse(null);
                RatioSet ratios = RatioCalculator.compute(upToYear, valuation, "Pas de cours à cette date");
                Map<Ratio, Double> values = new EnumMap<>(Ratio.class);
                ratios.values().forEach((r, v) -> values.put(r, v.value()));
                result.add(new YearRatios(end, valuation == null ? null : valuation.quotedPrice(), values));
            }
            return result;
        });
    }

    private RatioSet ratios(Company company) {
        FinancialHistory history = financials.history(company.getId());
        Optional<Quote> quote = market.latestQuote(company.getId());
        if (quote.isEmpty()) {
            return RatioCalculator.compute(history, null, "Cours de bourse indisponible");
        }
        Quote q = quote.get();
        if (q.asOf().isBefore(Instant.now(clock).minus(MAX_QUOTE_AGE))) {
            return RatioCalculator.compute(history, null,
                    "Dernier cours trop ancien (" + LocalDate.ofInstant(q.asOf(), clock.getZone()) + ")");
        }
        String reporting = reportingCurrency(history).orElse(q.currency());
        Optional<BigDecimal> price = market.convert(q.price(), q.currency(), reporting);
        if (price.isEmpty()) {
            return RatioCalculator.compute(history, null,
                    "Taux de change " + q.currency() + "/" + reporting + " indisponible");
        }
        return RatioCalculator.compute(history, new Valuation(price.get(), reporting, q.price(), q.currency(),
                q.asOf(), q.source(), company.getSharesAdmitted()), null);
    }

    /** Devise des comptes : celle des chiffres du dernier exercice. */
    private static Optional<String> reportingCurrency(FinancialHistory history) {
        return history.years().stream().findFirst()
                .flatMap(y -> y.metrics().values().stream().map(FinancialHistory.Value::currency).findFirst());
    }
}
