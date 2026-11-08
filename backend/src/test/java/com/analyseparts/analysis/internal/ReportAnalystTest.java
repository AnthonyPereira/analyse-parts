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
              {"titre":"Numéro un mondial","explication":"Leader de son marché.","extrait":2,
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
}
