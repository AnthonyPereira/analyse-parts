package com.analyseparts.scoring;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.company.Icb;
import com.analyseparts.financials.FinancialHistory;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.financials.Metric;
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
 * score courant stocké à tenir à jour quand un rapport ou un cours arrive. On enregistre la
 * calibration sectorielle, pour rester stable entre deux calibrages, et des photos des notes
 * pour leur historique ({@link ScoreHistoryService}).
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
        return ranking(c -> weightsFor(c, profile));
    }

    /** Classement avec des poids choisis librement (mode avancé), les mêmes pour toutes les sociétés. */
    public List<RankingEntry> ranking(Map<Ratio, Integer> weights) {
        return ranking(_ -> weights);
    }

    private List<RankingEntry> ranking(java.util.function.Function<Company, Map<Ratio, Integer>> weightsOf) {
        return scoredCompanies().stream()
                .map(c -> {
                    Score s = score(c, ratios(c), weightsOf.apply(c));
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

    static final String COOPERATIVE_CERTIFICATE = "Certificats coopératifs (CCI) : la bourse ne cote qu'une petite "
            + "partie du capital de la caisse régionale, son cours ne se compare pas à son bénéfice total";

    /** Code ICB du super-secteur des banques. */
    public static final String BANKS_SUPERSECTOR = "3010";

    /** Code ICB du super-secteur des assurances. */
    public static final String INSURANCE_SUPERSECTOR = "3030";

    /** Banques et assurances sont notées avec leurs propres poids dans chaque profil (voir {@link Profile}). */
    static Profile.Book bookOf(Company company) {
        String supersector = company.getIcb().map(icb -> icb.supersectorCode()).orElse(null);
        if (BANKS_SUPERSECTOR.equals(supersector)) {
            return Profile.Book.BANK;
        }
        return INSURANCE_SUPERSECTOR.equals(supersector) ? Profile.Book.INSURER : Profile.Book.STANDARD;
    }

    private static Map<Ratio, Integer> weightsFor(Company company, Profile profile) {
        return profile.weights(bookOf(company));
    }

    /** Les ISIN des sociétés notées (celles qui ont au moins un rapport). */
    public List<String> scoredIsins() {
        return scoredCompanies().stream().map(Company::getIsin).toList();
    }

    private List<Company> scoredCompanies() {
        Set<Long> withFilings = financials.companiesWithFilings();
        return companies.findAll().stream()
                .filter(c -> c.getId() != null && withFilings.contains(c.getId()))
                .toList();
    }

    private Score score(Company company, Profile profile) {
        return score(company, ratios(company), weightsFor(company, profile));
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
                        scores.put(p, score(c, ratios, weightsFor(c, p)).score());
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
        return companies.findByIsin(isin).map(company -> yearRatioSets(company).stream()
                .map(y -> {
                    Map<Ratio, Double> values = new EnumMap<>(Ratio.class);
                    y.ratios().values().forEach((r, v) -> values.put(r, v.value()));
                    return new YearRatios(y.fiscalYearEnd(), y.valuation() == null ? null : y.valuation().quotedPrice(),
                            values);
                })
                .toList());
    }

    /** Les 4 notes d'une société (null : pas assez de données pour ce profil). */
    public record ProfileScores(long companyId, String isin, LocalDate fiscalYearEnd, Map<Profile, Integer> scores) {
    }

    /** Les notes d'aujourd'hui, avec le dernier cours : ce que montre la fiche. */
    public Optional<ProfileScores> currentScores(String isin) {
        return companies.findByIsin(isin).map(company -> {
            RatioSet ratios = ratios(company);
            return profileScores(company, ratios, LocalDate.now(clock));
        });
    }

    /** Les notes d'aujourd'hui de toutes les sociétés notées (photo quotidienne). */
    public List<ProfileScores> allCurrentScores() {
        return scoredCompanies().stream().map(c -> profileScores(c, ratios(c), LocalDate.now(clock))).toList();
    }

    /**
     * Les notes qu'aurait eues la société à la clôture de chaque exercice passé, du plus ancien au
     * plus récent : mêmes calculs que {@link #ratioHistory}, puis les poids de chaque profil.
     * <p>
     * Approximation assumée : la calibration sectorielle est celle d'aujourd'hui.
     */
    public List<ProfileScores> pastScores(String isin) {
        return companies.findByIsin(isin)
                .map(company -> yearRatioSets(company).stream()
                        .map(y -> profileScores(company, y.ratios(), y.fiscalYearEnd()))
                        .toList())
                .orElse(List.of());
    }

    private ProfileScores profileScores(Company company, RatioSet ratios, LocalDate asOf) {
        SectorCalibration cal = calibration();
        Icb icb = company.getIcb().orElse(null);
        Map<Profile, Integer> scores = new EnumMap<>(Profile.class);
        LocalDate fiscalYearEnd = null;
        for (Profile p : Profile.values()) {
            Score s = ScoreCalculator.score(ratios, weightsFor(company, p), asOf, ratio -> cal.adjustment(ratio, icb));
            scores.put(p, s.score());
            fiscalYearEnd = s.fiscalYearEnd();
        }
        return new ProfileScores(company.getId(), company.getIsin(), fiscalYearEnd, scores);
    }

    /** Un exercice vu depuis sa clôture : ses ratios, et le cours de ce jour-là. */
    private record YearRatioSet(LocalDate fiscalYearEnd, Valuation valuation, RatioSet ratios) {
    }

    private List<YearRatioSet> yearRatioSets(Company company) {
        FinancialHistory history = history(company);
        String quoted = market.latestQuote(company.getId()).map(Quote::currency).orElse("EUR");
        String reporting = reportingCurrency(history).orElse(quoted);
        List<YearRatioSet> result = new ArrayList<>();
        for (int i = history.years().size() - 1; i >= 0; i--) { // du plus ancien au plus récent
            FinancialHistory upToYear = new FinancialHistory(history.years().subList(i, history.years().size()));
            LocalDate end = history.years().get(i).fiscalYearEnd();
            Valuation valuation = company.isCooperativeCertificate() ? null : market.closeOn(company.getId(), end)
                    .flatMap(close -> market.convert(close.close(), quoted, reporting)
                            .map(price -> new Valuation(price, reporting, close.close(), quoted,
                                    close.day().atStartOfDay(clock.getZone()).toInstant(), "CLOSE", null)))
                    .orElse(null);
            String noValuation = company.isCooperativeCertificate() ? COOPERATIVE_CERTIFICATE : "Pas de cours à cette date";
            result.add(new YearRatioSet(end, valuation, RatioCalculator.compute(upToYear, valuation, noValuation)));
        }
        return result;
    }

    private FinancialHistory history(Company company) {
        FinancialHistory history = financials.history(company.getId());
        return bookOf(company) == Profile.Book.INSURER ? withInsuranceRevenue(history) : history;
    }

    /**
     * Pour un assureur qui ne publie pas de chiffre d'affaires total (SCOR, Coface), le revenu des
     * contrats d'assurance (IFRS 17) en tient lieu. Réservé aux assurances : les banques publient
     * aussi ce revenu pour leur filiale d'assurance, et leur équivalent est le PNB.
     */
    static FinancialHistory withInsuranceRevenue(FinancialHistory history) {
        List<FinancialHistory.Year> years = history.years().stream().map(year -> {
            Optional<FinancialHistory.Value> insurance = year.get(Metric.INSURANCE_REVENUE);
            if (year.get(Metric.REVENUE).isPresent() || insurance.isEmpty()) {
                return year;
            }
            Map<Metric, FinancialHistory.Value> metrics = new EnumMap<>(Metric.class);
            metrics.putAll(year.metrics());
            metrics.put(Metric.REVENUE, insurance.get());
            return new FinancialHistory.Year(year.fiscalYearEnd(), metrics);
        }).toList();
        return new FinancialHistory(years);
    }

    private RatioSet ratios(Company company) {
        FinancialHistory history = history(company);
        if (company.isCooperativeCertificate()) {
            return RatioCalculator.compute(history, null, COOPERATIVE_CERTIFICATE);
        }
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
