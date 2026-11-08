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

        var index = new PassageSearch(embeddings).index(PASSAGES);
        var result = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model")
                .analyse("L'ORÉAL", LocalDate.of(2025, 12, 31), index);

        assertThat(result.themes()).hasSize(3);
        assertThat(result.themes()).allSatisfy(theme -> {
            assertThat(theme.points()).hasSize(1);
            var point = theme.points().get(0);
            assertThat(point.quote()).isEqualTo("L’Oréal est le numéro un mondial de la beauté");
            assertThat(point.passage()).isEqualTo(PASSAGES.get(0)); // numéro corrigé : le texte est dans [1]
        });
        assertThat(result.rejected()).isEqualTo(3); // l'invention, une fois par thème
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

        var index = new PassageSearch(embeddings).index(PASSAGES);
        var result = new ReportAnalyst(chat, JsonMapper.builder().build(), 16384, "test-model")
                .analyse("L'ORÉAL", LocalDate.of(2025, 12, 31), index);

        assertThat(result.themes()).allSatisfy(theme -> assertThat(theme.points())
                .extracting(p -> p.title()).containsExactly("Numéro un mondial", "Devises"));
        assertThat(result.rejected()).isZero();
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

    /** Vus sur le rapport de L'Oréal : des citations exactes, mais des points inutiles. */
    @Test
    void aHeadingOrAnExplanationThatRepeatsTheQuoteIsRejected() {
        assertThat(ReportAnalyst.weakness("Risques en matière d’Environnement", "Des risques pour l'environnement existent."))
                .contains("trop courte");
        assertThat(ReportAnalyst.weakness("Les principaux risques auxquels le Groupe est exposé sont décrits ci-après",
                "Les principaux risques auxquels le Groupe est exposé sont décrits ci-après."))
                .contains("répète");
        assertThat(ReportAnalyst.weakness("nous avons déposé un nombre record de 725 brevets, promettant toujours plus d’innovations",
                "Beaucoup de brevets protègent ses inventions : les concurrents ne peuvent pas les copier pendant vingt ans."))
                .isNull();
    }
}
