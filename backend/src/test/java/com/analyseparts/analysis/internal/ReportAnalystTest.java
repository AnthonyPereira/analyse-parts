package com.analyseparts.analysis.internal;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L'analyste avec un faux modèle : une citation exacte est gardée, une citation inventée écartée. */
class ReportAnalystTest {

    static final List<String> PASSAGES = List.of(
            "L’Oréal est le numéro un mondial de la beauté, avec 37 marques internationales.",
            "Le Groupe est exposé aux fluctuations des devises, en particulier du dollar américain.");

    static final String ANSWER = """
            {"points":[
              {"titre":"Numéro un mondial","explication":"Le groupe domine son marché dans le monde entier.","extrait":2,
               "citation":"L'Oréal est le numéro un mondial de la beauté"},
              {"titre":"Invention","explication":"Pas dans le texte.","extrait":1,
               "citation":"Le Groupe double son chiffre d'affaires tous les ans"}]}
            """;

    @Test
    void onlyPointsWithAQuoteFoundInTheReportAreKept() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1, 0}, new float[]{0, 1}));
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 1});
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER))));
        });

        var index = new PassageSearch(embeddings).buildIndex(PASSAGES);
        var result = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model")
                .analyse("L'ORÉAL", LocalDate.of(2025, 12, 31), index);

        assertThat(result.themes()).hasSize(3);
        var business = result.themes().get(0);
        assertThat(business.points()).hasSize(1);
        var point = business.points().get(0);
        assertThat(point.quote()).isEqualTo("L’Oréal est le numéro un mondial de la beauté");
        assertThat(point.passage()).isEqualTo(PASSAGES.get(0)); // numéro corrigé : le texte est dans [1]
        // la même phrase ne justifie pas un second point dans les thèmes suivants
        assertThat(result.themes().subList(1, 3)).allSatisfy(theme -> assertThat(theme.points()).isEmpty());
        assertThat(result.rejected()).isEqualTo(3); // l'invention, une fois par thème (même après un nouvel essai)
        String userPrompt = prompts.get(0).getInstructions().get(1).getText();
        assertThat(userPrompt).contains("[1] L’Oréal", "[2] Le Groupe", "exercice clos le 2025-12-31");
    }

    static final String TWO_POINTS = """
            {"points":[
              {"titre":"Numéro un mondial","explication":"Le groupe domine son marché dans le monde entier.","extrait":1,
               "citation":"L'Oréal est le numéro un mondial de la beauté"},
              {"titre":"Devises","explication":"Une baisse du dollar réduirait la valeur des ventes faites aux États-Unis.","extrait":2,
               "citation":"Le Groupe est exposé aux fluctuations des devises, en particulier du dollar américain"}]}
            """;

    /** Le modèle ne trie plus : tous les points vérifiés sont gardés, dans l'ordre du rapport. */
    @Test
    void everyVerifiedPointIsKeptInReportOrder() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1, 0}, new float[]{0, 1}));
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 1});
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(TWO_POINTS)))));

        var index = new PassageSearch(embeddings).buildIndex(PASSAGES);
        var result = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model")
                .analyse("L'ORÉAL", LocalDate.of(2025, 12, 31), index);

        assertThat(result.themes().get(0).points())
                .extracting(p -> p.title()).containsExactly("Numéro un mondial", "Devises");
        assertThat(result.rejected()).isZero();
    }

    /** La relecture retire un point jugé non soutenu ; un point sans avis est gardé. */
    @Test
    void aPointTheReviewJudgesUnsupportedIsRemoved() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1, 0}, new float[]{0, 1}));
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 1});
        List<String> reviews = new ArrayList<>();
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            if (prompt.getInstructions().get(0).getText().startsWith("Tu es un relecteur")) {
                reviews.add(prompt.getInstructions().get(1).getText());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                        {"avis":[{"point":2,"raison":"La citation ne parle pas de ce risque.","soutenu":false}]}"""))));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(TWO_POINTS))));
        });

        var index = new PassageSearch(embeddings).buildIndex(PASSAGES);
        var result = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model")
                .analyse("L'ORÉAL", LocalDate.of(2025, 12, 31), index);

        assertThat(result.themes().get(0).points())
                .extracting(p -> p.title()).containsExactly("Numéro un mondial");
        // « Devises » relu comme point 2 dans le premier thème ; ensuite seul dans sa liste (point 1),
        // le faux relecteur ne le juge plus et il est gardé
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(reviews.get(0)).contains("Point 2", "Titre : Devises", "Citation : « Le Groupe est exposé");
    }

    /** Une citation introuvable est redemandée une fois : la phrase exacte sauve le point. */
    @Test
    void aMissingQuoteIsAskedAgainOnce() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1, 0}, new float[]{0, 1}));
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 1});
        List<String> retries = new ArrayList<>();
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            if (prompt.getInstructions().get(0).getText().startsWith("Tu aides un analyste")) {
                retries.add(prompt.getInstructions().get(1).getText());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                        {"citations":[{"point":1,"citation":"Le Groupe est exposé aux fluctuations des devises, en particulier du dollar américain"}]}"""))));
            }
            // la citation résume la phrase au lieu de la recopier
            return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                    {"points":[{"titre":"Devises","explication":"Une baisse du dollar réduirait la valeur des ventes faites aux États-Unis.",
                      "extrait":2,"citation":"Le Groupe dépend du dollar"}]}"""))));
        });

        var index = new PassageSearch(embeddings).buildIndex(PASSAGES);
        var result = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model", "false")
                .analyse("L'ORÉAL", LocalDate.of(2025, 12, 31), index);

        assertThat(result.themes().get(0).points()).extracting(p -> p.quote())
                .containsExactly("Le Groupe est exposé aux fluctuations des devises, en particulier du dollar américain");
        assertThat(retries.get(0)).contains("Citation refusée : « Le Groupe dépend du dollar »", "Extrait : Le Groupe est exposé");
        assertThat(result.rejected()).isZero();
    }

    /** Une citation exacte mais trop courte (un titre) est aussi redemandée ; une explication creuse, non. */
    @Test
    void onlyMissingOrHeadingQuotesAreRetried() {
        var heading = new ReportAnalyst.AnswerPoint("Devises", "Une baisse du dollar réduirait les ventes aux États-Unis.", 2,
                "Le Groupe est exposé aux fluctuations");
        var repeats = new ReportAnalyst.AnswerPoint("Devises", "Le Groupe est exposé aux fluctuations des devises.", 2,
                "Le Groupe est exposé aux fluctuations des devises, en particulier du dollar américain");
        var outOfRange = new ReportAnalyst.AnswerPoint("Devises", "Une baisse du dollar réduirait les ventes.", 9, "Invention");
        assertThat(ReportAnalyst.check(heading, PASSAGES).retryable()).isTrue();
        assertThat(ReportAnalyst.check(repeats, PASSAGES).retryable()).isFalse();
        assertThat(ReportAnalyst.check(outOfRange, PASSAGES).retryable()).isFalse(); // extrait inconnu : rien à relire
    }

    /** Le modèle peut fondre deux points qu'il dit en double, seulement s'ils sont déjà un peu proches. */
    @Test
    void theModelMergesDuplicatesOnlyWhenTheyAreRelated() {
        var ventes = new com.analyseparts.analysis.ReportAnalysis.Point("Ventes de câbles", "", "a", "a");
        var fabrication = new com.analyseparts.analysis.ReportAnalysis.Point("Fabrication de câbles", "", "b", "b");
        var climat = new com.analyseparts.analysis.ReportAnalysis.Point("Climat et prêts", "", "c", "c");
        float[] x = {1, 0};
        float[] related = {0.8f, 0.6f}; // cosinus 0,80 avec x : au-dessus du garde-fou, sous le doublon automatique
        float[] far = {0, 1};
        var kept = ReportAnalyst.merge(List.of(ventes, fabrication, climat), List.of(x, related, far), List.of(x, related, far),
                List.of(List.of(0, 1), List.of(0, 2)), 10);
        assertThat(kept).containsExactly(ventes, climat); // le modèle se trompe sur « climat » : trop loin, gardé
    }

    /** Deux points au titre identique sont un doublon, quelles que soient leurs explications. */
    @Test
    void twoPointsWithTheSameTitleAreMerged() {
        var a = new com.analyseparts.analysis.ReportAnalysis.Point("Risques liés au changement climatique", "", "a", "a");
        var b = new com.analyseparts.analysis.ReportAnalysis.Point("Risques liés au changement climatique", "", "b", "b");
        float[] x = {1, 0};
        float[] y = {0, 1};
        assertThat(ReportAnalyst.merge(List.of(a, b), List.of(x, y), List.of(x, y), 10)).containsExactly(a);
    }

    @Test
    void theReviewSettingNamesTheTopicsToReview() {
        assertThat(ReportAnalyst.parseReviewedTopics("BUSINESS, advantages"))
                .containsExactly(ReportAnalyst.Topic.BUSINESS, ReportAnalyst.Topic.ADVANTAGES);
        assertThat(ReportAnalyst.parseReviewedTopics("true")).hasSize(3);
        assertThat(ReportAnalyst.parseReviewedTopics("false")).isEmpty();
        assertThat(ReportAnalyst.parseReviewedTopics("")).isEmpty();
    }

    @Test
    void quotesOverlapWhenOneContainsTheOther() {
        assertThat(ReportAnalyst.overlaps("Le Groupe est présent dans 64 pays et compte plus de 180 000 collaborateurs",
                "Le Groupe est présent dans 64 pays")).isTrue();
        assertThat(ReportAnalyst.overlaps("Le Groupe est présent dans 64 pays", "Le Groupe emploie 180 000 personnes")).isFalse();
    }

    /** Doublon seulement si les titres ET les explications ont le même sens (vecteurs proches). */
    @Test
    void aPointIsMergedOnlyWhenTitleAndExplanationBothMatch() {
        var change = new com.analyseparts.analysis.ReportAnalysis.Point("Taux de change", "", "a", "a");
        var devises = new com.analyseparts.analysis.ReportAnalysis.Point("Devises", "", "b", "b");
        var taux = new com.analyseparts.analysis.ReportAnalysis.Point("Taux d'intérêt", "", "c", "c");
        float[] x = {1, 0};
        float[] y = {0, 1};
        float[] near = {0.95f, 0.312f}; // cosinus 0,95 avec x
        // devises : titre et explication proches de « change » → regroupé ;
        // taux d'intérêt : titre proche mais explication différente → gardé
        var kept = ReportAnalyst.merge(List.of(change, devises, taux), List.of(x, near, near), List.of(x, near, y), 10);
        assertThat(kept).containsExactly(change, taux);
        assertThat(ReportAnalyst.merge(List.of(change, devises, taux), List.of(x, y, x), List.of(y, x, x), 2))
                .containsExactly(change, devises);
    }

    /** Le modèle remplit les champs dans l'ordre du schéma : l'extrait et la citation d'abord. */
    @Test
    void theAnswerFormatAlwaysAsksForTheQuoteFirst() {
        var mapper = JsonMapper.builder().build();
        var properties = mapper.readTree(mapper.writeValueAsString(ReportAnalyst.SCHEMA))
                .get("properties").get("points").get("items").get("properties");
        assertThat(properties.propertyNames()).containsExactly("extrait", "citation", "titre", "explication");
    }

    /** Le classement du modèle est appliqué ; un numéro inconnu ou répété est ignoré, un point oublié jamais perdu. */
    @Test
    void theModelOrderIsAppliedWithoutLosingAPoint() {
        var a = new com.analyseparts.analysis.ReportAnalysis.Point("A", "a", "qa", "pa");
        var b = new com.analyseparts.analysis.ReportAnalysis.Point("B", "b", "qb", "pb");
        var c = new com.analyseparts.analysis.ReportAnalysis.Point("C", "c", "qc", "pc");
        var d = new com.analyseparts.analysis.ReportAnalysis.Point("D", "d", "qd", "pd");

        assertThat(ReportAnalyst.applyOrder(List.of(a, b, c), List.of(3, 1, 2))).containsExactly(c, a, b);
        assertThat(ReportAnalyst.applyOrder(List.of(a, b, c, d), List.of(4, 9, 4, 0, 2))).containsExactly(d, b, a, c);
        assertThat(ReportAnalyst.applyOrder(List.of(a, b), null)).containsExactly(a, b);
    }

    @Test
    void pointsAreRankedByTheModelAndKeptAsIsWhenItsAnswerIsUnreadable() {
        var a = new com.analyseparts.analysis.ReportAnalysis.Point("Change", "Le dollar pèse sur les ventes.", "q1", "p1");
        var b = new com.analyseparts.analysis.ReportAnalysis.Point("Climat", "Des sites exposés aux inondations.", "q2", "p2");
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            String answer = prompts.size() == 1 ? "{\"ordre\":[2,1]}" : "pas du JSON";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        var analyst = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model");

        assertThat(analyst.rankByImportance("L'ORÉAL", ReportAnalyst.Topic.RISKS, List.of(a, b))).containsExactly(b, a);
        assertThat(prompts.get(0).getUserMessage().getText())
                .contains("Point 1 : Change", "Point 2 : Climat", "du plus grave au moins grave");
        assertThat(analyst.rankByImportance("L'ORÉAL", ReportAnalyst.Topic.RISKS, List.of(a, b))).containsExactly(a, b);
    }

    /** Vus sur le rapport de L'Oréal : des citations exactes, mais des points inutiles. */
    @Test
    void aHeadingOrAnExplanationThatRepeatsTheQuoteIsRejected() {
        assertThat(ReportAnalyst.findWeakness("Risques en matière d’Environnement", "Des risques pour l'environnement existent."))
                .contains("trop courte");
        assertThat(ReportAnalyst.findWeakness("Les principaux risques auxquels le Groupe est exposé sont décrits ci-après",
                "Les principaux risques auxquels le Groupe est exposé sont décrits ci-après."))
                .contains("répète");
        assertThat(ReportAnalyst.findWeakness("nous avons déposé un nombre record de 725 brevets, promettant toujours plus d’innovations",
                "Beaucoup de brevets protègent ses inventions : les concurrents ne peuvent pas les copier pendant vingt ans."))
                .isNull();
        // un intitulé chiffré est gardé (Covivio)
        assertThat(ReportAnalyst.findWeakness("1.3.1 Bureaux : 49% du patrimoine de Covivio",
                "Les immeubles de bureaux sont la plus grosse activité, près de la moitié de la valeur des biens."))
                .isNull();
    }
}
