package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.ReportAnalysis.Point;
import com.analyseparts.analysis.ReportAnalysis.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pose les questions au modèle de langage, thème par thème, et ne garde que les points dont la
 * citation figure réellement dans le rapport.
 * <p>
 * Le rapport entier ne tient pas dans la mémoire du modèle. Pour chaque thème, en trois temps :
 * <ol>
 *   <li><b>lecture</b> : de nombreuses recherches (une par sous-sujet : chaque catégorie de risque,
 *       chaque façon de gagner de l'argent…) choisissent jusqu'à 42 passages, lus par lots de 14 ;
 *       pour l'activité, le premier lot est le chapitre de présentation du groupe, lu d'un seul
 *       tenant. Chaque lot donne des points, dont la citation est vérifiée ({@link Quotes}) ; une
 *       citation introuvable ou réduite à un titre est redemandée une fois. Une phrase ne justifie
 *       qu'un point dans tout le rapport ;</li>
 *   <li><b>relecture</b> (réglable) : le modèle juge, point par point, si la citation appuie
 *       vraiment le point ;</li>
 *   <li><b>regroupement</b> : les doublons n'en font qu'un (voir {@link #merge(Topic, List, PassageSearch.Index)}).
 *       Le modèle peut signaler deux points qui disent la même chose, mais il ne trie pas par
 *       importance : un essai où il triait lui-même écartait des risques importants (change, taux,
 *       climat) et en gardait de secondaires.</li>
 * </ol>
 * Les points restent dans l'ordre du rapport : une société présente d'habitude ses risques les plus
 * importants en premier.
 */
@Component
public class ReportAnalyst {

    private static final Logger log = LoggerFactory.getLogger(ReportAnalyst.class);

    /** Passages envoyés au modèle en une fois : environ 6 000 tokens de texte. */
    static final int BATCH = 14;

    /** Passages lus d'un seul tenant dans le chapitre de présentation : un lot entier. */
    static final int CHAPTER_LENGTH = BATCH;

    /** Points demandés par lot de passages (7 pour les risques : un lot en décrit souvent davantage). */
    static final int POINTS_PER_BATCH = 5;

    /** Un thème : sa question au modèle et les recherches qui trouvent les bons passages. */
    enum Topic {
        BUSINESS("Ce que fait la société",
                "Que fait le groupe et comment gagne-t-il de l'argent ? (métiers, produits, clients, marchés, sources de revenus, poids de chaque activité ou zone). Décris l'activité du groupe tout entier, pas les comptes de la seule société mère.",
                8, 2,
                List.of("Présentation du groupe, de ses activités, de ses métiers et de ses principaux marchés",
                        "Modèle d'affaires : comment la société crée de la valeur et génère son chiffre d'affaires",
                        "Répartition du chiffre d'affaires par activité ou par division, avec leur poids et leur croissance",
                        "Répartition du chiffre d'affaires par zone géographique et par pays",
                        // banques et assurances : ni « chiffre d'affaires » ni « divisions »
                        "Répartition des revenus par pôle et par métier : produit net bancaire, primes d'assurance, banque de détail, banque de financement et d'investissement, gestion d'actifs",
                        "Clients, canaux de distribution, réseau de vente et modes de commercialisation",
                        "Chiffres clés de l'année : chiffre d'affaires, résultat, effectifs, nombre de sites et de pays"),
                // le chapitre où le rapport présente le groupe : lu d'un seul tenant, avant les recherches
                // (pas les résultats de l'année par division : le rapport de gestion l'emporterait)
                List.of("Présentation du Groupe : son métier, sa raison d'être, ses fondamentaux et son histoire",
                        "L'organisation du Groupe : ses divisions, ses zones géographiques, ses filiales et ses marques",
                        "Le marché sur lequel le Groupe intervient, sa taille, sa croissance et sa position face aux concurrents",
                        "Le modèle d'affaires et la chaîne de valeur : recherche, production, distribution, clients")),
        ADVANTAGES("Ses avantages face aux concurrents",
                "Quels avantages concurrentiels la société met-elle en avant ? Préfère les faits vérifiables (parts de marché, position de numéro un, brevets, taille, réseau, coûts) aux simples affirmations.",
                8, 2,
                List.of("Atouts et avantages concurrentiels du groupe face à ses concurrents",
                        "Position de leader, numéro un mondial, parts de marché et taille face aux concurrents",
                        "Marques reconnues, portefeuille de marques et fidélité des clients",
                        "Recherche et innovation : brevets, technologies, dépenses de recherche et développement",
                        "Réseau industriel et logistique, coûts de production, économies d'échelle, barrières à l'entrée",
                        "Stratégie du groupe et priorités stratégiques pour les prochaines années")),
        RISKS("Ses principaux risques",
                "Quels sont les risques pour la société, tels qu'elle les présente elle-même ? Préfère les risques propres à son activité aux risques génériques. Pour chaque risque, explique ce qui pourrait arriver et ce que cela changerait pour la société (ventes, marges, image, dette). Ne cite pas un simple titre de risque : choisis une phrase qui le décrit.",
                16, 3,
                List.of("Description d'un facteur de risque : ce qui peut arriver, son impact possible sur l'activité, les résultats ou la situation financière du Groupe, et les mesures pour le maîtriser",
                        "Risques géopolitiques, économiques et politiques des pays où le groupe est implanté, droits de douane, tensions commerciales",
                        "Risque lié à la concurrence, à l'évolution de la demande et des habitudes des consommateurs",
                        "Risque de dépendance à un client, à un fournisseur, à une matière première ou à un pays",
                        "Risque de rupture de la chaîne d'approvisionnement, disponibilité des produits, prix des matières premières et de l'énergie",
                        "Risque lié aux systèmes d'information, aux cyberattaques et à la protection des données",
                        "Risque lié à la qualité et à la sécurité des produits, rappels de produits, image et réputation",
                        "Risques juridiques et réglementaires : évolution de la réglementation, non-conformité, litiges, enquêtes",
                        "Risques liés au changement climatique et à l'environnement",
                        "Risque lié aux ressources humaines : recrutement, talents, personnes clés",
                        "Risque de change, risque de taux d'intérêt et risque d'inflation",
                        "Risque de liquidité, endettement, financement et notation de crédit",
                        "Risque lié aux acquisitions, aux participations et à la dépréciation des écarts d'acquisition"),
                // le chapitre « Facteurs de risques », d'abord : un passage qui commence par le tableau de
                // classement des risques ne remonte dans aucune recherche, même s'il finit par décrire un
                // risque majeur (chez L'Oréal, l'environnement politique et les droits de douane)
                List.of("Facteurs de risques : les principaux risques auxquels le Groupe est exposé, classés par catégorie et par importance résiduelle",
                        "Risques liés à l'activité : identification du risque et gestion du risque",
                        "Implantation géographique et environnement économique et politique : perturbations politiques ou économiques dans les pays où le Groupe réalise ses ventes"));

        final String title;
        final String question;
        final int maxPoints;
        final int batches;
        /**
         * Points demandés par lot. Pour les risques, 5 ne suffisaient pas : un lot de facteurs de
         * risque en décrit souvent plus, et le modèle laissait de côté des risques réels (chez
         * L'Oréal, les tensions géopolitiques et les droits de douane, pourtant écrits en toutes lettres).
         */
        int pointsPerBatch() {
            return this == RISKS ? 7 : POINTS_PER_BATCH;
        }
        final List<String> searches;
        /** Ce dont parle le chapitre à lire d'un seul tenant (voir {@link PassageSearch.Index#chapter}). */
        final List<String> chapters;

        /** Les passages plus proches d'une de ces recherches que des recherches du thème sont écartés. */
        static final List<String> AVOID = List.of(
                "Plan de vigilance : évaluation et audit des fournisseurs et des sous-traitants, droits humains, santé et sécurité chez les fournisseurs",
                "Comptes annuels de la société mère : prestations de services et redevances facturées aux filiales, résultat social de la société mère",
                "Sommaire, table de concordance et renvois vers les chapitres du document",
                "Statuts de la société : objet social, forme juridique, durée et siège",
                // une vraie clause d'objet social : plus proche des passages concernés qu'un simple intitulé
                // (chez Covivio, « l'exploitation de fonds de commerce dans le secteur du tourisme » passait pour une activité)
                "La société a pour objet, en France et à l'étranger : l'acquisition, la détention, la gestion, la cession et l'exploitation de tous biens et fonds de commerce, ainsi que toutes opérations se rattachant directement ou indirectement à cet objet",
                "Règles et méthodes comptables : comptabilisation du chiffre d'affaires, des remises, des frais et des instruments financiers",
                // vu chez L'Oréal comme « avantages » : « méthode des flux de trésorerie actualisés », « durée d'utilité indéfinie »
                // (sans parler de « marques » : la présentation du groupe en parle aussi, et serait écartée)
                "Notes annexes aux comptes : test de perte de valeur annuel, valeur d'utilité calculée selon la méthode des flux de trésorerie actualisés, taux d'actualisation et taux de croissance à l'infini, durée d'utilité indéfinie");

        Topic(String title, String question, int maxPoints, int batches, List<String> searches) {
            this(title, question, maxPoints, batches, searches, List.of());
        }

        Topic(String title, String question, int maxPoints, int batches, List<String> searches, List<String> chapters) {
            this.title = title;
            this.question = question;
            this.maxPoints = maxPoints;
            this.batches = batches;
            this.searches = searches;
            this.chapters = chapters;
        }

        /** Les passages à lire : le chapitre d'abord, complété par les recherches. */
        List<Integer> passages(PassageSearch.Index index) {
            int max = BATCH * batches;
            // la présentation ouvre le rapport ; les facteurs de risques peuvent être plus loin
            double within = this == BUSINESS ? 0.25 : 1;
            List<Integer> read = index.chapter(chapters, AVOID, Math.min(CHAPTER_LENGTH, max), within);
            if (!read.isEmpty()) {
                log.info("{} : chapitre lu, passages {} à {} : {}", this, read.get(0), read.get(read.size() - 1), read.stream()
                        .filter(i -> !read.contains(i - 1))
                        .map(i -> "[" + i + "] " + index.passage(i).substring(0, Math.min(80, index.passage(i).length())))
                        .toList());
            }
            return index.search(searches, AVOID, read, max, max);
        }
    }

    static final String SYSTEM = """
            Tu es un analyste financier. Tu lis des extraits du rapport annuel d'une société cotée pour \
            un investisseur débutant, et tu réponds en français simple, sans jargon inutile.
            Règles :
            - Tu utilises UNIQUEMENT les extraits fournis. Si une information n'y figure pas, tu ne \
            l'inventes pas et tu ne la complètes pas avec tes connaissances.
            - Chaque point s'appuie sur un extrait : tu donnes son numéro, et tu recopies MOT POUR MOT une \
            phrase courte de cet extrait (moins de 300 caractères), dans sa langue d'origine, sans la \
            reformuler ni la traduire. La citation ne reprend pas le numéro de l'extrait. C'est une \
            phrase complète, pas un titre.
            - Le titre fait 3 à 10 mots, écrits avec tes mots : ce n'est pas une phrase du rapport.
            - L'explication dit, avec tes mots, ce que le débutant doit comprendre : elle ne recopie pas \
            la citation.
            - Tu ne t'appuies ni sur l'objet social des statuts (« la société a pour objet… »), qui dit \
            ce que la société a le droit de faire et non ce qu'elle fait, ni sur une règle ou une \
            méthode comptable.
            - Tu ne donnes aucun conseil d'achat ou de vente.""";

    /**
     * Le format de réponse imposé au modèle (JSON Schema) : Ollama refuse toute autre forme.
     * <p>
     * Le modèle écrit les champs <b>dans l'ordre du schéma</b>. Cet ordre doit donc être fixe :
     * {@code Map.of} change d'ordre à chaque démarrage de Java, et un démarrage sur deux le modèle
     * écrivait la citation avant de choisir l'extrait, et recopiait alors « [1] » au lieu d'une
     * phrase (25 points écartés sur L'Oréal au lieu de 2). L'ordre choisi fait d'abord trouver la
     * phrase, puis seulement la résumer : le titre et l'explication s'appuient sur la citation.
     */
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("points", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", ordered(
                                    "extrait", Map.of("type", "integer"),
                                    "citation", Map.of("type", "string"),
                                    "titre", Map.of("type", "string"),
                                    "explication", Map.of("type", "string")),
                            "required", List.of("extrait", "citation", "titre", "explication")))),
            "required", List.of("points"));

    /** Une table qui garde l'ordre de ses clés (clé, valeur, clé, valeur…). */
    static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /** La réponse du modèle, telle que décrite par {@link #SCHEMA}. */
    record Answer(List<AnswerPoint> points) {
    }

    record AnswerPoint(String titre, String explication, Integer extrait, String citation) {
    }

    /** Les réponses d'un rapport et le nombre de points écartés. */
    record Result(List<Theme> themes, int rejected) {
    }

    private final ChatModel chat;
    private final JsonMapper json;
    private final int contextTokens;
    private final String model;

    /** Les thèmes relus ({@link #review}) : pas les risques, où la relecture retirait des risques réels. */
    private final java.util.Set<Topic> reviewed;

    @org.springframework.beans.factory.annotation.Autowired
    public ReportAnalyst(ChatModel chat, JsonMapper json,
                         @Value("${analyse-parts.analysis.context-tokens:10240}") int contextTokens,
                         @Value("${spring.ai.ollama.chat.options.model}") String model,
                         @Value("${analyse-parts.analysis.review:BUSINESS,ADVANTAGES}") String review) {
        this.chat = chat;
        this.json = json;
        this.contextTokens = contextTokens;
        this.model = model;
        this.reviewed = reviewedTopics(review);
    }

    ReportAnalyst(ChatModel chat, JsonMapper json, int contextTokens, String model) {
        this(chat, json, contextTokens, model, "true");
    }

    /** « true » : tous les thèmes ; « false » ou vide : aucun ; sinon la liste des thèmes (BUSINESS,ADVANTAGES). */
    static java.util.Set<Topic> reviewedTopics(String setting) {
        String s = setting == null ? "" : setting.strip();
        if (s.equalsIgnoreCase("true")) {
            return java.util.EnumSet.allOf(Topic.class);
        }
        java.util.Set<Topic> topics = java.util.EnumSet.noneOf(Topic.class);
        for (String name : s.split(",")) {
            if (!name.isBlank() && !name.strip().equalsIgnoreCase("false")) {
                topics.add(Topic.valueOf(name.strip().toUpperCase()));
            }
        }
        return topics;
    }

    Result analyse(String company, LocalDate periodEnd, PassageSearch.Index index) {
        List<Theme> themes = new ArrayList<>();
        int rejected = 0;
        // une même phrase ne justifie qu'un point dans tout le rapport : chez BNP Paribas, « présent
        // dans 64 pays » servait à la fois l'activité et un avantage
        List<String> usedQuotes = new ArrayList<>();
        for (Topic topic : Topic.values()) {
            // dans l'ordre du rapport : un lot couvre des passages voisins, donc une même section
            List<Integer> chosen = topic.passages(index);
            List<Point> candidates = new ArrayList<>();
            for (int from = 0; from < chosen.size(); from += BATCH) {
                List<String> passages = chosen.subList(from, Math.min(chosen.size(), from + BATCH))
                        .stream().map(index::passage).toList();
                Answer answer;
                try {
                    answer = ask(SYSTEM, prompt(company, periodEnd, topic, passages), SCHEMA, Answer.class);
                } catch (tools.jackson.core.JacksonException e) {
                    // réponse coupée par la limite de longueur : on perd ce lot, pas toute la lecture
                    log.warn("Lot de passages ignoré ({}), réponse illisible : {}", topic, e.getOriginalMessage());
                    continue;
                }
                List<AnswerPoint> points = answer.points() == null ? List.of() : answer.points();
                List<AnswerPoint> toRetry = new ArrayList<>();
                for (AnswerPoint p : points) {
                    Outcome outcome = check(p, passages);
                    if (outcome.retryable()) {
                        toRetry.add(p);
                    } else if (outcome.point() == null) {
                        rejected++;
                        log.info("Point écarté ({}), {} : {}", topic, outcome.weakness(), p.citation());
                    } else {
                        add(candidates, outcome.point(), usedQuotes, topic);
                    }
                }
                for (AnswerPoint p : retryQuotes(topic, passages, toRetry)) {
                    Outcome outcome = check(p, passages);
                    if (outcome.point() == null) {
                        rejected++;
                        log.info("Point écarté ({}) après un nouvel essai, {} : {}", topic, outcome.weakness(), p.citation());
                    } else {
                        log.info("Point sauvé ({}) par un nouvel essai de citation : {}", topic, p.titre());
                        add(candidates, outcome.point(), usedQuotes, topic);
                    }
                }
            }
            List<Point> supported = reviewed.contains(topic) ? review(topic, candidates) : candidates;
            rejected += candidates.size() - supported.size();
            candidates = supported;
            List<Point> kept = merge(topic, candidates, index);
            log.info("{} : {} passages lus, {} points proposés, {} gardés ; regroupés avec un autre : {}", topic,
                    chosen.size(), candidates.size(), kept.size(),
                    candidates.stream().filter(c -> !kept.contains(c)).map(Point::title).toList());
            kept.forEach(p -> usedQuotes.add(p.quote()));
            themes.add(new Theme(topic.name(), topic.title, kept));
        }
        return new Result(themes, rejected);
    }

    /**
     * Ce que devient un point proposé : gardé ({@code point}), écarté ({@code weakness}), ou à
     * redemander quand sa citation est introuvable ou n'est qu'un titre.
     */
    record Outcome(Point point, String weakness, boolean retryable) {
    }

    static Outcome check(AnswerPoint p, List<String> passages) {
        int preferred = p.extrait() == null ? -1 : p.extrait() - 1; // numérotés à partir de 1
        var match = Quotes.find(p.citation(), passages, preferred);
        if (blank(p.titre())) {
            return new Outcome(null, "sans titre", false);
        }
        if (match.isEmpty()) {
            return new Outcome(null, "citation introuvable", preferred >= 0 && preferred < passages.size());
        }
        String weakness = weakness(match.get().text(), p.explication());
        if (weakness != null) {
            return new Outcome(null, weakness, weakness.startsWith("citation trop courte"));
        }
        return new Outcome(new Point(p.titre().strip(), p.explication() == null ? "" : p.explication().strip(),
                match.get().text(), passages.get(match.get().passage())), null, false);
    }

    /** Ajoute un point, sauf si sa citation est déjà prise, dans ce thème ou dans un thème précédent. */
    private static void add(List<Point> candidates, Point point, List<String> usedQuotes, Topic topic) {
        if (candidates.stream().anyMatch(c -> c.quote().equals(point.quote()))) {
            return;
        }
        if (usedQuotes.stream().anyMatch(q -> overlaps(q, point.quote()))) {
            log.info("Point écarté ({}), citation déjà utilisée dans un autre thème : {}", topic, point.title());
            return;
        }
        candidates.add(point);
    }

    /** Deux citations se recouvrent quand l'une contient l'autre (au texte simplifié près). */
    static boolean overlaps(String a, String b) {
        String x = Quotes.Normalized.of(a).text, y = Quotes.Normalized.of(b).text;
        return x.contains(y) || y.contains(x);
    }

    static final String RETRY_SYSTEM = """
            Tu aides un analyste financier. Pour chaque point, il a donné une citation qui ne figure pas \
            mot pour mot dans l'extrait, ou qui n'est qu'un titre.
            Recopie MOT POUR MOT, dans l'extrait indiqué, une phrase complète (au moins une dizaine de \
            mots, moins de 300 caractères) qui appuie le point, sans la reformuler ni la traduire. Si \
            aucune phrase de l'extrait n'appuie le point, renvoie une citation vide.""";

    static final Map<String, Object> RETRY_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("citations", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", ordered(
                                    "point", Map.of("type", "integer"),
                                    "citation", Map.of("type", "string")),
                            "required", List.of("point", "citation")))),
            "required", List.of("citations"));

    record Retry(List<RetryQuote> citations) {
    }

    record RetryQuote(Integer point, String citation) {
    }

    /**
     * Un point dont la citation est introuvable ou n'est qu'un titre n'est pas forcément faux : le
     * modèle a souvent résumé ou coupé la phrase. On lui redemande, une fois, une phrase exacte du
     * même extrait. Renvoie les points avec leur nouvelle citation (à vérifier comme les autres).
     */
    private List<AnswerPoint> retryQuotes(Topic topic, List<String> passages, List<AnswerPoint> points) {
        if (points.isEmpty()) {
            return List.of();
        }
        Retry retry;
        try {
            retry = ask(RETRY_SYSTEM, retryPrompt(passages, points), RETRY_SCHEMA, Retry.class);
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("Nouvel essai de citation illisible ({}) : {}", topic, e.getOriginalMessage());
            retry = new Retry(List.of());
        }
        Map<Integer, String> quotes = new java.util.HashMap<>();
        for (RetryQuote q : retry.citations() == null ? List.<RetryQuote>of() : retry.citations()) {
            if (q.point() != null && !blank(q.citation())) {
                quotes.putIfAbsent(q.point() - 1, q.citation()); // numérotés à partir de 1
            }
        }
        List<AnswerPoint> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            AnswerPoint p = points.get(i);
            // sans nouvelle citation, l'ancienne est revérifiée et le point écarté avec sa raison
            result.add(new AnswerPoint(p.titre(), p.explication(), p.extrait(), quotes.getOrDefault(i, p.citation())));
        }
        return result;
    }

    static String retryPrompt(List<String> passages, List<AnswerPoint> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            AnswerPoint p = points.get(i);
            sb.append("Point ").append(i + 1).append('\n')
                    .append("Titre : ").append(p.titre()).append('\n')
                    .append("Explication : ").append(p.explication()).append('\n')
                    .append("Citation refusée : « ").append(p.citation()).append(" »\n")
                    .append("Extrait : ").append(passages.get(p.extrait() - 1)).append("\n\n");
        }
        sb.append("Donne une citation pour chacun des ").append(points.size()).append(" points.");
        return sb.toString();
    }

    /** Points relus en une fois : 8 extraits d'environ 1 800 caractères tiennent dans le contexte. */
    static final int REVIEW_BATCH = 8;

    static final String REVIEW_SYSTEM = """
            Tu es un relecteur exigeant. Un analyste a tiré des points d'un rapport annuel ; chaque \
            point a un titre, une explication et une citation exacte, tirée de l'extrait fourni.
            Pour chaque point, vérifie que la citation, lue dans son extrait, appuie vraiment le titre \
            et l'explication, et répond à la question posée. Réponds non si :
            - la citation parle d'autre chose que le point ;
            - la citation dit le contraire du point (par exemple « aucun impact significatif » pour \
            présenter un risque important) ;
            - la citation est une règle comptable, une méthode de calcul ou une phrase générale, et \
            pas un fait sur la société qui réponde à la question ;
            - l'explication affirme un fait ou un chiffre absent de l'extrait.
            Sinon, réponds oui. Donne d'abord ta raison, en une phrase courte.""";

    /** Le format de la relecture : la raison avant le verdict, pour que le verdict en découle. */
    static final Map<String, Object> REVIEW_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("avis", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", ordered(
                                    "point", Map.of("type", "integer"),
                                    "raison", Map.of("type", "string"),
                                    "soutenu", Map.of("type", "boolean")),
                            "required", List.of("point", "raison", "soutenu")))),
            "required", List.of("avis"));

    record Review(List<Verdict> avis) {
    }

    record Verdict(Integer point, String raison, Boolean soutenu) {
    }

    /**
     * La <b>relecture</b> : le modèle vérifie, point par point, que la citation appuie bien ce que
     * dit le point. {@link Quotes} prouve qu'une citation existe ; seule une lecture dit si elle
     * parle bien du sujet (vu sur L'Oréal : « aucun impact significatif » cité pour un risque
     * climatique, une règle de comptabilisation citée comme avantage). Un point sans avis, ou une
     * relecture illisible, est gardé : la relecture ne retire que ce qu'elle a jugé.
     */
    private List<Point> review(Topic topic, List<Point> candidates) {
        List<Point> supported = new ArrayList<>();
        for (int from = 0; from < candidates.size(); from += REVIEW_BATCH) {
            List<Point> batch = candidates.subList(from, Math.min(candidates.size(), from + REVIEW_BATCH));
            Map<Integer, Verdict> verdicts = new java.util.HashMap<>();
            try {
                Review review = ask(REVIEW_SYSTEM, reviewPrompt(topic, batch), REVIEW_SCHEMA, Review.class);
                for (Verdict v : review.avis() == null ? List.<Verdict>of() : review.avis()) {
                    if (v.point() != null) {
                        verdicts.putIfAbsent(v.point() - 1, v); // numérotés à partir de 1
                    }
                }
            } catch (tools.jackson.core.JacksonException e) {
                log.warn("Relecture illisible ({}), points gardés : {}", topic, e.getOriginalMessage());
            }
            for (int i = 0; i < batch.size(); i++) {
                Verdict v = verdicts.get(i);
                if (v != null && Boolean.FALSE.equals(v.soutenu())) {
                    log.info("Point écarté ({}), relecture : {} — {}", topic, v.raison(), batch.get(i).title());
                } else {
                    supported.add(batch.get(i));
                }
            }
        }
        return supported;
    }

    static String reviewPrompt(Topic topic, List<Point> points) {
        StringBuilder sb = new StringBuilder("Question posée à l'analyste : ").append(topic.question).append('\n');
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            sb.append("\nPoint ").append(i + 1).append('\n')
                    .append("Titre : ").append(p.title()).append('\n')
                    .append("Explication : ").append(p.explanation()).append('\n')
                    .append("Citation : « ").append(p.quote()).append(" »\n")
                    .append("Extrait : ").append(p.passage()).append('\n');
        }
        sb.append("\nDonne un avis pour chacun des ").append(points.size()).append(" points.");
        return sb.toString();
    }

    /**
     * Deux points sont un doublon quand leurs titres <b>et</b> leurs explications sont proches. Mesuré
     * sur L'Oréal : « variations des devises » et « fluctuations des taux de change » donnent 0,86 et
     * 0,86 (doublon) ; « taux d'intérêt » et « taux de change » donnent 0,92 pour les titres mais 0,83
     * pour les explications (deux risques différents). Le seuil laisse passer quelques doublons plutôt
     * que de fondre deux risques en un.
     */
    static final double SAME_MEANING = 0.85;

    /**
     * Les points sans doublon (le premier, dans l'ordre du rapport, est gardé), au plus
     * {@code topic.maxPoints}. Trois façons d'être un doublon :
     * <ol>
     *   <li>titres et explications proches ({@link #SAME_MEANING}) ;</li>
     *   <li>le même titre, mot pour mot (deux « Risques liés au changement climatique » chez L'Oréal) ;</li>
     *   <li>le modèle dit que deux points décrivent la même chose. Les embeddings seuls ne suffisent
     *       pas : chez Nexans, « Ventes de câbles et services d'installation » et « Fabrication et
     *       installation de câbles » n'ont que 0,70 de proximité d'explication, moins que deux risques
     *       climatiques bien distincts de BNP Paribas (0,77). Garde-fou : le modèle ne peut fondre que
     *       des points déjà un peu proches ({@link #RELATED_TITLES} ou {@link #RELATED_TEXTS}).</li>
     * </ol>
     */
    private List<Point> merge(Topic topic, List<Point> candidates, PassageSearch.Index index) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<float[]> titles = index.embed(candidates.stream().map(Point::title).toList());
        List<float[]> texts = index.embed(candidates.stream().map(p -> p.title() + " : " + p.explanation()).toList());
        return merge(candidates, titles, texts, duplicateGroups(topic, candidates), topic.maxPoints);
    }

    static List<Point> merge(List<Point> candidates, List<float[]> titles, List<float[]> texts, int max) {
        return merge(candidates, titles, texts, List.of(), max);
    }

    /**
     * En dessous, deux points ne sont jamais fondus, même si le modèle les dit en double. Mesuré :
     * vrais doublons à 0,875 / 0,70 (titres / explications, Nexans), 0,78 / 0,89 (Nexans) et
     * 0,72 / 0,82 (BNP Paribas) ; points distincts que le modèle disait en double à 0,43 / 0,78
     * (« leader mondial » et « portefeuille de marques », L'Oréal) et 0,73 / 0,77 (deux risques
     * climatiques, BNP Paribas).
     */
    static final double RELATED_TITLES = 0.75;
    static final double RELATED_TEXTS = 0.80;

    static List<Point> merge(List<Point> candidates, List<float[]> titles, List<float[]> texts,
                             List<List<Integer>> modelGroups, int max) {
        java.util.Set<Integer> removed = new java.util.HashSet<>();
        for (List<Integer> group : modelGroups) {
            List<Integer> members = group.stream().filter(i -> i >= 0 && i < candidates.size()).distinct().sorted().toList();
            for (int j = 1; j < members.size(); j++) {
                int first = members.get(0), other = members.get(j);
                if (PassageSearch.dot(titles.get(first), titles.get(other)) >= RELATED_TITLES
                        || PassageSearch.dot(texts.get(first), texts.get(other)) >= RELATED_TEXTS) {
                    removed.add(other);
                }
            }
        }
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < candidates.size() && kept.size() < max; i++) {
            int candidate = i;
            String title = Quotes.Normalized.of(candidates.get(i).title()).text;
            boolean duplicate = removed.contains(i) || kept.stream().anyMatch(k ->
                    PassageSearch.dot(titles.get(k), titles.get(candidate)) >= SAME_MEANING
                            && PassageSearch.dot(texts.get(k), texts.get(candidate)) >= SAME_MEANING
                            || Quotes.Normalized.of(candidates.get(k).title()).text.equals(title));
            if (!duplicate) {
                kept.add(i);
            }
        }
        return kept.stream().map(candidates::get).toList();
    }

    static final String DUPLICATES_SYSTEM = """
            Tu relis la liste des points qu'un analyste a tirés d'un rapport annuel, sous un même thème.
            Repère les points qui décrivent exactement la même chose avec d'autres mots : la même \
            division ou activité, le même avantage, le même risque. Deux risques sont le même risque \
            quand ils décrivent le même événement ou la même cause, pas quand ils ont les mêmes \
            conséquences : presque tous les risques peuvent coûter de l'argent ou nuire à l'image. \
            Deux points qui parlent de sujets voisins mais différents ne sont pas des doublons : le \
            risque de change et le risque de taux, les dégâts du climat sur les sites et les pertes \
            sur les prêts, un défaut de produit et une rupture d'approvisionnement, une division et \
            le groupe tout entier.
            Donne chaque groupe de doublons : les numéros des points, ta raison en une phrase courte, \
            puis ton verdict (doublon : true seulement s'ils décrivent bien la même chose). S'il n'y a \
            aucun doublon, renvoie une liste vide.""";

    static final Map<String, Object> DUPLICATES_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("doublons", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", ordered(
                                    "points", Map.of("type", "array", "items", Map.of("type", "integer")),
                                    "raison", Map.of("type", "string"),
                                    "doublon", Map.of("type", "boolean")),
                            "required", List.of("points", "raison", "doublon")))),
            "required", List.of("doublons"));

    record Duplicates(List<DuplicateGroup> doublons) {
    }

    /**
     * Un groupe proposé. Le verdict vient après la raison : sans lui, le modèle listait aussi des
     * paires dont la raison disait « ne décrivent pas la même chose » (BNP Paribas), et elles
     * étaient fondues.
     */
    record DuplicateGroup(List<Integer> points, String raison, Boolean doublon) {
    }

    /** Les groupes de doublons selon le modèle (indices à partir de 0) ; vide si sa réponse est illisible. */
    private List<List<Integer>> duplicateGroups(Topic topic, List<Point> candidates) {
        StringBuilder sb = new StringBuilder("Thème : ").append(topic.title).append('\n');
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("\nPoint ").append(i + 1).append(" : ").append(candidates.get(i).title())
                    .append(" — ").append(candidates.get(i).explanation());
        }
        try {
            Duplicates answer = ask(DUPLICATES_SYSTEM, sb.toString(), DUPLICATES_SCHEMA, Duplicates.class);
            List<List<Integer>> groups = new ArrayList<>();
            for (DuplicateGroup g : answer.doublons() == null ? List.<DuplicateGroup>of() : answer.doublons()) {
                if (Boolean.TRUE.equals(g.doublon()) && g.points() != null && g.points().size() >= 2) {
                    log.info("{} : doublons selon le modèle {} ({})", topic, g.points(), g.raison());
                    groups.add(g.points().stream().filter(java.util.Objects::nonNull).map(i -> i - 1).toList());
                }
            }
            return groups;
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("Recherche de doublons illisible ({}) : {}", topic, e.getOriginalMessage());
            return List.of();
        }
    }

    static String prompt(String company, LocalDate periodEnd, Topic topic, List<String> passages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Société : ").append(company).append(". Rapport annuel de l'exercice clos le ")
                .append(periodEnd).append(".\n\nExtraits du rapport :\n");
        for (int i = 0; i < passages.size(); i++) {
            sb.append("\n[").append(i + 1).append("] ").append(passages.get(i)).append('\n');
        }
        sb.append("\nQuestion : ").append(topic.question)
                .append("\nDonne au plus ").append(topic.pointsPerBatch())
                .append(" points, du plus important au moins important. Si les extraits ne permettent pas de répondre, renvoie une liste vide.");
        return sb.toString();
    }

    /**
     * Longueur maximale d'une réponse, en tokens. Avec un JSON imposé, le modèle peut tourner en
     * boucle (des espaces sans fin) : sans limite, l'appel ne se termine jamais. Une réponse coupée
     * n'est plus du JSON valide, et le lot est alors ignoré.
     */
    static final int MAX_ANSWER_TOKENS = 2500;

    private <T> T ask(String system, String userPrompt, Map<String, Object> schema, Class<T> type) {
        return LocalModel.ask(chat, json, model, contextTokens, MAX_ANSWER_TOKENS, system, userPrompt, schema, type);
    }

    /** Une citation de moins de 8 mots est un titre ou un nom (« Risques en matière d'Environnement »). */
    static final int MIN_QUOTE_WORDS = 8;

    /** Une citation chiffrée (pourcentage, montant) suffit à partir de 5 mots. */
    static final int MIN_FIGURE_QUOTE_WORDS = 5;

    /** L'explication doit apporter au moins 3 mots (de 4 lettres ou plus) absents de la citation. */
    static final int MIN_NEW_WORDS = 3;

    /**
     * Un point dont la citation existe peut quand même être inutile : une citation trop courte (un
     * titre) ou une explication qui ne fait que la répéter. Renvoie la raison, ou {@code null}.
     */
    static String weakness(String quote, String explanation) {
        int length = words(quote, 2).size();
        // un intitulé chiffré est une information (« Bureaux : 49% du patrimoine de Covivio »)
        boolean figure = quote.matches("(?s).*\\d\\s*(%|Md|M€|€|millions?|milliards?).*");
        if (length < MIN_QUOTE_WORDS && !(figure && length >= MIN_FIGURE_QUOTE_WORDS)) {
            return "citation trop courte (un titre ?)";
        }
        java.util.Set<String> quoted = new java.util.HashSet<>(words(quote, 4));
        long added = words(explanation == null ? "" : explanation, 4).stream()
                .distinct().filter(w -> !quoted.contains(w)).count();
        return added < MIN_NEW_WORDS ? "explication qui répète la citation" : null;
    }

    /** Les mots d'au moins {@code minLetters} lettres, simplifiés (minuscules, sans accents). */
    private static List<String> words(String text, int minLetters) {
        return java.util.Arrays.stream(Quotes.Normalized.of(text).text.split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= minLetters).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
