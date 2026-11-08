package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.ReportAnalysis.Point;
import com.analyseparts.analysis.ReportAnalysis.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
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
 * Pour chaque thème : la recherche sémantique choisit une quinzaine de passages (le rapport entier
 * ne tient pas dans la mémoire du modèle), le modèle répond en JSON selon un schéma imposé, puis
 * chaque citation est vérifiée ({@link Quotes}).
 */
@Component
public class ReportAnalyst {

    private static final Logger log = LoggerFactory.getLogger(ReportAnalyst.class);

    /** Passages envoyés au modèle par thème : environ 6 000 tokens de texte. */
    static final int PASSAGES_PER_TOPIC = 14;

    /** Un thème : sa question au modèle et les recherches qui trouvent les bons passages. */
    enum Topic {
        BUSINESS("Ce que fait la société",
                "Que fait la société et comment gagne-t-elle de l'argent ? (métiers, produits, clients, marchés, sources de revenus)",
                4,
                List.of("Présentation du groupe, de ses activités, de ses métiers et de ses principaux marchés",
                        "Modèle d'affaires : comment la société crée de la valeur et génère son chiffre d'affaires",
                        "Répartition du chiffre d'affaires par activité, par zone géographique et par type de clients")),
        ADVANTAGES("Ses avantages face aux concurrents",
                "Quels avantages concurrentiels la société met-elle en avant ? Préfère les faits vérifiables (parts de marché, position de numéro un, brevets, taille, réseau, coûts) aux simples affirmations.",
                4,
                List.of("Atouts et avantages concurrentiels du groupe face à ses concurrents",
                        "Position de leader, parts de marché, marques reconnues, brevets, technologies, barrières à l'entrée",
                        "Stratégie du groupe et priorités stratégiques pour les prochaines années")),
        RISKS("Ses principaux risques",
                "Quels sont les principaux risques pour la société, tels qu'elle les présente elle-même ? Préfère les risques propres à son activité aux risques génériques.",
                5,
                List.of("Principaux facteurs de risques spécifiques au groupe et leur niveau de criticité",
                        "Risques liés à l'activité, à la concurrence, à la réglementation et à l'évolution des marchés",
                        "Risques financiers : change, liquidité, endettement, dépendance à des clients ou à des fournisseurs"));

        final String title;
        final String question;
        final int maxPoints;
        final List<String> searches;

        Topic(String title, String question, int maxPoints, List<String> searches) {
            this.title = title;
            this.question = question;
            this.maxPoints = maxPoints;
            this.searches = searches;
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
            reformuler ni la traduire. La citation ne reprend pas le numéro de l'extrait.
            - Tu ne donnes aucun conseil d'achat ou de vente.""";

    /** Le format de réponse imposé au modèle (JSON Schema) : Ollama refuse toute autre forme. */
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("points", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "titre", Map.of("type", "string"),
                                    "explication", Map.of("type", "string"),
                                    "extrait", Map.of("type", "integer"),
                                    "citation", Map.of("type", "string")),
                            "required", List.of("titre", "explication", "extrait", "citation")))),
            "required", List.of("points"));

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

    public ReportAnalyst(ChatModel chat, JsonMapper json,
                         @Value("${analyse-parts.analysis.context-tokens:16384}") int contextTokens,
                         @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.chat = chat;
        this.json = json;
        this.contextTokens = contextTokens;
        this.model = model;
    }

    Result analyse(String company, LocalDate periodEnd, PassageSearch.Index index) {
        List<Theme> themes = new ArrayList<>();
        int rejected = 0;
        for (Topic topic : Topic.values()) {
            List<Integer> chosen = index.search(topic.searches, 8, PASSAGES_PER_TOPIC);
            List<String> passages = chosen.stream().map(index::passage).toList();
            Answer answer = ask(prompt(company, periodEnd, topic, passages));
            List<Point> points = new ArrayList<>();
            for (AnswerPoint p : answer.points() == null ? List.<AnswerPoint>of() : answer.points()) {
                int preferred = p.extrait() == null ? -1 : p.extrait() - 1; // numérotés à partir de 1
                var match = Quotes.find(p.citation(), passages, preferred);
                if (match.isEmpty() || blank(p.titre())) {
                    rejected++;
                    log.info("Point écarté ({}), citation introuvable : {}", topic, p.citation());
                    continue;
                }
                if (points.size() < topic.maxPoints) {
                    points.add(new Point(p.titre().strip(), p.explication() == null ? "" : p.explication().strip(),
                            match.get().text(), passages.get(match.get().passage())));
                }
            }
            themes.add(new Theme(topic.name(), topic.title, points));
        }
        return new Result(themes, rejected);
    }

    static String prompt(String company, LocalDate periodEnd, Topic topic, List<String> passages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Société : ").append(company).append(". Rapport annuel de l'exercice clos le ")
                .append(periodEnd).append(".\n\nExtraits du rapport :\n");
        for (int i = 0; i < passages.size(); i++) {
            sb.append("\n[").append(i + 1).append("] ").append(passages.get(i)).append('\n');
        }
        sb.append("\nQuestion : ").append(topic.question)
                .append("\nDonne au plus ").append(topic.maxPoints)
                .append(" points, du plus important au moins important. Si les extraits ne permettent pas de répondre, renvoie une liste vide.");
        return sb.toString();
    }

    private Answer ask(String userPrompt) {
        // Spring AI 2.0 : les options passées à l'appel remplacent celles de la configuration, modèle compris
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(model)
                .format(SCHEMA)
                .disableThinking() // qwen3 « réfléchit » par défaut : plus long, pas plus fidèle aux extraits
                .numCtx(contextTokens)
                .temperature(0.1) // peu de hasard : on veut recopier, pas créer
                .build();
        String content = chat.call(new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(userPrompt)), options))
                .getResult().getOutput().getText();
        return json.readValue(content, Answer.class);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
