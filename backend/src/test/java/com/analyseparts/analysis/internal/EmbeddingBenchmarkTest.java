package com.analyseparts.analysis.internal;

import com.analyseparts.financials.FilingRef;
import com.analyseparts.ingestion.esef.AmfFilingsClient;
import com.analyseparts.ingestion.esef.EsefReportDocuments;
import com.analyseparts.ingestion.esef.FilingsXbrlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Banc d'essai des modèles d'embedding : lequel choisit les passages les plus utiles pour chaque
 * thème (activité, avantages, risques) ?
 * <p>
 * Méthode (évaluation « par mise en commun », comme les campagnes TREC) :
 * <ol>
 *   <li>chaque modèle découpe et indexe les mêmes rapports, puis choisit les passages de chaque
 *       thème avec le code de production ({@code Topic.selectPassages}) ;</li>
 *   <li>on réunit tous les passages choisis par au moins un modèle ; qwen3:14b juge chacun une fois
 *       (utile ou non pour le thème), sans savoir quel modèle l'a choisi ;</li>
 *   <li>précision = part des passages choisis qui sont utiles ; rappel relatif = part des passages
 *       utiles de la mise en commun que le modèle a trouvés.</li>
 * </ol>
 * Lancement (Ollama allumé, carte graphique libre, environ 30 min) :
 * {@code EMBEDDING_BENCHMARK=1 ./mvnw test -Dtest=EmbeddingBenchmarkTest}. Les embeddings ne
 * sont pas gardés ; les rapports et les jugements sont mis en cache dans
 * {@code target/embedding-benchmark/}, et le résultat écrit dans {@code resultats.md}. Les variantes
 * « avec préfixe » réutilisent les embeddings des passages de leur modèle : leur durée d'indexation
 * est donc proche de zéro.
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_BENCHMARK", matches = ".+")
class EmbeddingBenchmarkTest {

    private static final Path DIR = Path.of("target", "embedding-benchmark");
    private static final String JUDGE = "qwen3:14b";
    private static final int JUDGE_BATCH = 12;

    /** Sept secteurs : cosmétique, banque, industrie, immobilier, santé, énergie, services informatiques. */
    private static final List<Report> REPORTS = List.of(
            new Report("FR0000120321", "L'Oréal", "https://fr.ftp.opendatasoft.com/datadila/INFOFI/144/8888/01/FC144619472_20260318.zip"),
            new Report("FR0000131104", "BNP Paribas", "/R0MUWSFPU8MPRO8K5P83/2025-12-31/ESEF/FR/1/bnp-2025-12-31-1-fr.json"),
            new Report("FR0000121972", "Schneider Electric", "/969500A1YF1XUYYXS284/2025-12-31/ESEF/FR/0/969500A1YF1XUYYXS284-2025-12-31-fr.json"),
            new Report("FR0010040865", "Gecina", "/9695003E4MMA10IBTR26/2025-12-31/ESEF/FR/0/9695003E4MMA10IBTR26-2025-12-31-1-fr.json"),
            new Report("FR0000120578", "Sanofi", "/549300E9PC51EN656011/2025-12-31/ESEF/FR/0/sny-2025-12-31-2-fr.json"),
            new Report("FR0000120271", "TotalEnergies", "/529900S21EQ1BO4ESM68/2025-12-31/ESEF/FR/0/529900S21EQ1BO4ESM68-2025-12-31-1-fr.json"),
            new Report("FR0000125338", "Capgemini", "https://fr.ftp.opendatasoft.com/datadila/INFOFI/183/8888/01/FC183620359_20260323.xbri"));

    /**
     * Les configurations comparées. qwen3-embedding et snowflake-arctic-embed2 sont entraînés avec
     * un préfixe devant les questions (pas devant les passages) : on mesure avec et sans.
     */
    private static final List<Config> CONFIGS = List.of(
            new Config("bge-m3", "bge-m3", ""),
            new Config("qwen3-embedding:0.6b", "qwen3-embedding:0.6b", ""),
            new Config("qwen3-embedding:0.6b + consigne", "qwen3-embedding:0.6b",
                    "Instruct: Given a question about a company, retrieve passages of its annual report that answer it\nQuery: "),
            new Config("snowflake-arctic-embed2", "snowflake-arctic-embed2", ""),
            new Config("snowflake-arctic-embed2 + query:", "snowflake-arctic-embed2", "query: "));

    record Report(String isin, String name, String url) {
    }

    record Config(String label, String model, String queryPrefix) {
    }

    record Selection(Map<ReportAnalyst.Topic, List<Integer>> passages, long embeddingMs) {
    }

    /** Délai long : le premier appel charge le modèle en mémoire graphique, et un lot de 32 passages prend du temps. */
    private final OllamaApi ollama = OllamaApi.builder()
            .restClientBuilder(RestClient.builder().requestFactory(createRequestFactory()))
            .build();

    private static org.springframework.http.client.ClientHttpRequestFactory createRequestFactory() {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMinutes(5));
        return factory;
    }
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void compareEmbeddingModels() throws IOException {
        Files.createDirectories(DIR);
        Map<String, CachingEmbeddings> models = new HashMap<>();
        Map<String, List<String>> passagesByIsin = new LinkedHashMap<>();
        // isin -> configuration -> passages choisis par thème
        Map<String, Map<Config, Selection>> selections = new LinkedHashMap<>();

        for (Report report : REPORTS) {
            List<String> passages = ReportText.extractPassages(new ByteArrayInputStream(downloadXhtml(report)));
            passagesByIsin.put(report.isin(), passages);
            Map<Config, Selection> byConfig = new LinkedHashMap<>();
            for (Config config : CONFIGS) {
                CachingEmbeddings base = models.computeIfAbsent(config.model(), this::createEmbeddings);
                long start = System.currentTimeMillis();
                PassageSearch.Index index = new PassageSearch(base.withQueryPrefix(config.queryPrefix())).buildIndex(passages);
                long embedded = System.currentTimeMillis() - start;
                Map<ReportAnalyst.Topic, List<Integer>> chosen = new LinkedHashMap<>();
                for (ReportAnalyst.Topic topic : ReportAnalyst.Topic.values()) {
                    chosen.put(topic, topic.selectPassages(index));
                }
                byConfig.put(config, new Selection(chosen, embedded));
                System.out.printf("%s / %s : %d passages indexés en %d s%n", report.name(), config.label(),
                        passages.size(), embedded / 1000);
            }
            selections.put(report.isin(), byConfig);
            models.values().forEach(CachingEmbeddings::clear); // un rapport à la fois en mémoire
        }

        ChatModel judge = OllamaChatModel.builder().ollamaApi(ollama).build();
        Map<String, Map<ReportAnalyst.Topic, Map<Integer, Boolean>>> verdicts = new LinkedHashMap<>();
        for (Report report : REPORTS) {
            Map<ReportAnalyst.Topic, Map<Integer, Boolean>> byTopic = new LinkedHashMap<>();
            for (ReportAnalyst.Topic topic : ReportAnalyst.Topic.values()) {
                Set<Integer> pool = new TreeSet<>();
                selections.get(report.isin()).values().forEach(s -> pool.addAll(s.passages().get(topic)));
                byTopic.put(topic, judgePool(judge, report, topic, passagesByIsin.get(report.isin()), pool));
            }
            verdicts.put(report.isin(), byTopic);
        }

        String table = buildReport(selections, verdicts);
        Files.writeString(DIR.resolve("resultats.md"), table);
        System.out.println(table);
    }

    private byte[] downloadXhtml(Report report) throws IOException {
        Path cached = DIR.resolve(report.isin() + ".xhtml");
        if (Files.exists(cached)) {
            return Files.readAllBytes(cached);
        }
        var documents = new EsefReportDocuments(
                new AmfFilingsClient(RestClient.builder(), "https://www.info-financiere.gouv.fr"),
                new FilingsXbrlClient(RestClient.builder(), "https://filings.xbrl.org", Duration.ofSeconds(2),
                        List.of(Duration.ofSeconds(30), Duration.ofSeconds(60))));
        byte[] xhtml = documents.downloadXhtml(new FilingRef(report.isin(), LocalDate.of(2025, 12, 31), report.url()));
        Files.write(cached, xhtml);
        return xhtml;
    }

    private CachingEmbeddings createEmbeddings(String model) {
        return new CachingEmbeddings(OllamaEmbeddingModel.builder()
                .ollamaApi(ollama)
                .options(OllamaEmbeddingOptions.builder().model(model).build())
                .build());
    }

    /** Juge chaque passage de la mise en commun une seule fois ; les jugements sont gardés sur disque. */
    private Map<Integer, Boolean> judgePool(ChatModel judge, Report report, ReportAnalyst.Topic topic,
                                            List<String> passages, Set<Integer> pool) throws IOException {
        Path file = DIR.resolve("jugements-" + report.isin() + "-" + topic + ".properties");
        Properties saved = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                saved.load(r);
            }
        }
        List<Integer> todo = pool.stream().filter(i -> !saved.containsKey(String.valueOf(i))).toList();
        for (int from = 0; from < todo.size(); from += JUDGE_BATCH) {
            List<Integer> batch = todo.subList(from, Math.min(todo.size(), from + JUDGE_BATCH));
            Map<Integer, Boolean> answers = askJudge(judge, report, topic, passages, batch);
            batch.forEach(i -> saved.setProperty(String.valueOf(i), String.valueOf(answers.getOrDefault(i, false))));
            try (Writer w = Files.newBufferedWriter(file)) {
                saved.store(w, report.name() + " " + topic);
            }
            System.out.printf("%s / %s : %d/%d passages jugés%n", report.name(), topic,
                    Math.min(from + JUDGE_BATCH, todo.size()), todo.size());
        }
        Map<Integer, Boolean> result = new HashMap<>();
        pool.forEach(i -> result.put(i, Boolean.parseBoolean(saved.getProperty(String.valueOf(i)))));
        return result;
    }

    static final String JUDGE_SYSTEM = """
            Tu évalues des extraits du rapport annuel d'une société cotée. Pour un thème donné, tu dis, \
            pour chaque extrait, s'il apporte une information utile pour répondre à la question du thème : \
            un fait précis sur la société elle-même. Un sommaire, un titre seul, une méthode comptable, \
            une généralité ou un sujet voisin ne sont pas utiles.""";

    record Verdicts(List<Verdict> avis) {
    }

    record Verdict(Integer extrait, Boolean utile) {
    }

    private Map<Integer, Boolean> askJudge(ChatModel judge, Report report, ReportAnalyst.Topic topic,
                                           List<String> passages, List<Integer> batch) {
        StringBuilder prompt = new StringBuilder()
                .append("Société : ").append(report.name())
                .append("\nThème : ").append(topic.title)
                .append("\nQuestion : ").append(topic.question)
                .append("\n\nExtraits :\n");
        for (int n = 0; n < batch.size(); n++) {
            prompt.append('[').append(n + 1).append("] ").append(passages.get(batch.get(n))).append("\n\n");
        }
        prompt.append("Réponds pour chacun des ").append(batch.size()).append(" extraits, par son numéro.");
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("avis", Map.of("type", "array", "items", Map.of("type", "object",
                        "properties", ReportAnalyst.buildOrderedMap(
                                "extrait", Map.of("type", "integer"),
                                "utile", Map.of("type", "boolean")),
                        "required", List.of("extrait", "utile")))),
                "required", List.of("avis"));
        Map<Integer, Boolean> answers = new HashMap<>();
        for (int attempt = 0; attempt < 2 && answers.size() < batch.size(); attempt++) {
            try {
                Verdicts v = LocalModel.ask(judge, json, JUDGE, 10240, 1000, JUDGE_SYSTEM, prompt.toString(), schema, Verdicts.class);
                for (Verdict verdict : v.avis()) {
                    if (verdict.extrait() != null && verdict.extrait() >= 1 && verdict.extrait() <= batch.size()) {
                        answers.put(batch.get(verdict.extrait() - 1), Boolean.TRUE.equals(verdict.utile()));
                    }
                }
            } catch (RuntimeException e) {
                System.out.println("Jugement illisible, nouvel essai : " + e.getMessage());
            }
        }
        return answers;
    }

    private static String buildReport(Map<String, Map<Config, Selection>> selections,
                                      Map<String, Map<ReportAnalyst.Topic, Map<Integer, Boolean>>> verdicts) {
        StringBuilder out = new StringBuilder("## Embeddings : passages choisis et jugés utiles par qwen3:14b\n\n")
                .append(REPORTS.size()).append(" rapports annuels (exercice 2025) : ")
                .append(String.join(", ", REPORTS.stream().map(Report::name).toList())).append(".\n\n")
                .append("| Configuration | Thème | Choisis | Utiles | Précision | Rappel relatif | Commun avec bge-m3 | Indexation par rapport |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Config config : CONFIGS) {
            List<ReportAnalyst.Topic> topics = new ArrayList<>(List.of(ReportAnalyst.Topic.values()));
            topics.add(null); // la ligne « tous thèmes »
            for (ReportAnalyst.Topic topic : topics) {
                int chosen = 0, useful = 0, poolUseful = 0, common = 0;
                long ms = 0;
                for (Report report : REPORTS) {
                    Selection s = selections.get(report.isin()).get(config);
                    Selection reference = selections.get(report.isin()).get(CONFIGS.get(0));
                    ms += s.embeddingMs();
                    for (ReportAnalyst.Topic t : ReportAnalyst.Topic.values()) {
                        if (topic != null && t != topic) {
                            continue;
                        }
                        Map<Integer, Boolean> judged = verdicts.get(report.isin()).get(t);
                        List<Integer> mine = s.passages().get(t);
                        Set<Integer> ref = new HashSet<>(reference.passages().get(t));
                        chosen += mine.size();
                        useful += (int) mine.stream().filter(judged::get).count();
                        poolUseful += (int) judged.values().stream().filter(b -> b).count();
                        common += (int) mine.stream().filter(ref::contains).count();
                    }
                }
                out.append(String.format("| %s | %s | %d | %d | %.0f %% | %.0f %% | %.0f %% | %s |%n",
                        config.label(), topic == null ? "**tous**" : topic.title, chosen, useful,
                        100.0 * useful / Math.max(1, chosen), 100.0 * useful / Math.max(1, poolUseful),
                        100.0 * common / Math.max(1, chosen),
                        topic == null ? (ms / REPORTS.size() / 1000) + " s" : ""));
            }
        }
        return out.toString();
    }

    /**
     * Garde les embeddings des passages d'un rapport : les variantes avec ou sans préfixe partagent les
     * mêmes passages, seules les questions changent.
     */
    static final class CachingEmbeddings {
        private final EmbeddingModel delegate;
        private final Map<String, float[]> cache = new HashMap<>();

        CachingEmbeddings(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        void clear() {
            cache.clear();
        }

        EmbeddingModel withQueryPrefix(String prefix) {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    return delegate.call(request);
                }

                @Override
                public float[] embed(Document document) {
                    return delegate.embed(document);
                }

                /** Une question seule (PassageSearch interroge l'index une question à la fois). */
                @Override
                public float[] embed(String text) {
                    return delegate.embed(prefix + text);
                }

                /** Des passages : calculés une fois par modèle. */
                @Override
                public List<float[]> embed(List<String> texts) {
                    List<String> missing = texts.stream().filter(t -> !cache.containsKey(t)).distinct().toList();
                    if (!missing.isEmpty()) {
                        List<float[]> vectors = delegate.embed(missing);
                        for (int i = 0; i < missing.size(); i++) {
                            cache.put(missing.get(i), vectors.get(i));
                        }
                    }
                    return texts.stream().map(cache::get).toList();
                }
            };
        }
    }
}
