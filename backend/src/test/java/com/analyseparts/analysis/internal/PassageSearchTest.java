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

        var index = new PassageSearch(embeddings).buildIndex(List.of("a", "b", "c"));

        assertThat(index.search(List.of("risques"), 3, 3)).containsExactly(0, 1, 2);
        assertThat(index.search(List.of("risques"), List.of("vigilance"), 3, 3)).containsExactly(0, 2);
    }

    /**
     * Le chapitre est la suite de passages la plus proche en moyenne : ni la lettre du président
     * (le passage le plus proche à lui seul), ni le sommaire (écarté), qui citent tous deux l'activité.
     * Embeddings à trois dimensions : (activité, autre sujet, sommaire).
     */
    @Test
    void theChapterIsTheStretchOfPassagesClosestOnAverage() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(
                new float[]{0.6f, 0, 0.8f},  // 0 : le sommaire
                new float[]{0.95f, 0.31f, 0}, // 1 : la lettre du président
                new float[]{0, 1, 0},         // 2 : la gouvernance
                new float[]{0.8f, 0.6f, 0},   // 3 : 1.1 Présentation du Groupe
                new float[]{0.8f, 0.6f, 0},   // 4 : la suite du chapitre
                new float[]{0.8f, 0.6f, 0},   // 5
                new float[]{0, 1, 0}));       // 6 : le chapitre suivant
        when(embeddings.embed("activité")).thenReturn(new float[]{1, 0, 0});
        when(embeddings.embed("sommaire")).thenReturn(new float[]{0, 0, 1});

        var index = new PassageSearch(embeddings).buildIndex(List.of("a", "b", "c", "d", "e", "f", "g"));

        assertThat(index.findChapter(List.of("activité"), List.of("sommaire"), 3)).containsExactly(3, 4, 5);
        // limité au début du rapport : le chapitre doit commencer dans les 3 premiers passages
        assertThat(index.findChapter(List.of("activité"), List.of("sommaire"), 3, 0.15)).containsExactly(1, 2, 3);
        // complété par la recherche : les passages les plus proches qui ne sont pas déjà lus
        assertThat(index.search(List.of("activité"), List.of("sommaire"), List.of(3, 4, 5), 5, 4)).containsExactly(1, 3, 4, 5);
    }

    @Test
    void densestFindsTheStretchWithTheLargestSum() {
        assertThat(PassageSearch.Index.findDensestStart(new double[]{0.9, 0, 0.5, 0.5, 0.5, 0}, 3)).isEqualTo(2);
        assertThat(PassageSearch.Index.findDensestStart(new double[]{0.9, 0.1}, 5)).isZero(); // rapport plus court que le chapitre
    }

    /** Vu chez Nexans : le sommaire, lu comme un chapitre, ne donnait que des titres. */
    @Test
    void aTableOfContentsIsRecognised() {
        assertThat(PassageSearch.Index.isContents("1.3.2 Autres activités 1.3.3 Activités hors Électrification 1.4 Innovation "
                + "1.4.1 Recherche 1.5 Activités du Groupe durant l'année 2025 1.6.1 Investissements 1.6.2 Contrats importants")).isTrue();
        assertThat(PassageSearch.Index.isContents("1.3.1 Bureaux : 49% du patrimoine de Covivio. Le patrimoine de bureaux "
                + "s'élève à 8,2 Md€, dont 2.1 Paris en part du Groupe.")).isFalse();
    }
}
