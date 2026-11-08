package com.analyseparts.analysis.internal;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** La recherche avec de faux embeddings à deux dimensions : (risques, fournisseurs). */
class PassageSearchTest {

    @Test
    void aPassageCloserToAnAvoidedSubjectIsNeverChosen() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(
                new float[]{1, 0},     // 0 : un facteur de risque
                new float[]{0.6f, 1},  // 1 : le plan de vigilance, qui parle aussi de risques
                new float[]{0.8f, 0.3f})); // 2 : un risque lié à un fournisseur
        when(embeddings.embed("risques")).thenReturn(new float[]{1, 0});
        when(embeddings.embed("vigilance")).thenReturn(new float[]{0, 1});

        var index = new PassageSearch(embeddings).index(List.of("a", "b", "c"));

        assertThat(index.search(List.of("risques"), 3, 3)).containsExactly(0, 1, 2);
        assertThat(index.search(List.of("risques"), List.of("vigilance"), 3, 3)).containsExactly(0, 2);
    }
}
