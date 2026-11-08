package com.analyseparts.analysis.internal;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Recherche <b>sémantique</b> dans un rapport : retrouver les passages qui parlent d'un sujet, même
 * sans les mots exacts de la question (« risques de change » trouve « fluctuations des devises »).
 * <p>
 * Chaque passage est transformé par le modèle {@code bge-m3} en <b>embedding</b> : une liste de
 * 1 024 nombres qui résume son sens. Deux textes de sens proche ont des embeddings proches ; on
 * mesure cette proximité par le <b>cosinus</b> de l'angle entre les deux listes (1 = même sens).
 * <p>
 * Pas de base vectorielle (pgvector) : on ne cherche que dans <b>un</b> rapport à la fois, soit
 * environ 1 000 passages, et comparer 1 000 listes de nombres prend moins d'une milliseconde. Les
 * embeddings sont jetés après l'analyse ; il faudrait les garder pour chercher dans tous les
 * rapports à la fois.
 */
@Component
public class PassageSearch {

    /** Nombre de passages envoyés au modèle d'embeddings par requête. */
    private static final int BATCH = 32;

    private final EmbeddingModel embeddings;

    public PassageSearch(EmbeddingModel embeddings) {
        this.embeddings = embeddings;
    }

    /** Les passages d'un rapport, avec leurs embeddings, prêts à être interrogés. */
    public Index index(List<String> passages) {
        return new Index(passages, embed(passages));
    }

    /** Les embeddings de plusieurs textes, ramenés à une longueur de 1. */
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
            embeddings.embed(batch).forEach(v -> vectors.add(normalize(v)));
        }
        return vectors;
    }

    public final class Index {
        private final List<String> passages;
        private final List<float[]> vectors;

        private Index(List<String> passages, List<float[]> vectors) {
            this.passages = passages;
            this.vectors = vectors;
        }

        public int size() {
            return passages.size();
        }

        /**
         * Les passages les plus proches de plusieurs questions : les {@code perQuery} meilleurs de
         * chacune, sans doublon, au plus {@code max}, remis dans l'ordre du rapport (plus facile à
         * lire pour le modèle).
         */
        public List<Integer> search(List<String> queries, int perQuery, int max) {
            return search(queries, List.of(), perQuery, max);
        }

        /**
         * Pareil, en écartant les passages hors sujet : un passage plus proche d'une des recherches
         * {@code avoid} (le plan de vigilance sur les fournisseurs, les comptes de la seule société
         * mère…) que de toutes les recherches {@code queries} n'est jamais choisi.
         */
        public List<Integer> search(List<String> queries, List<String> avoid, int perQuery, int max) {
            Set<Integer> chosen = new LinkedHashSet<>();
            List<float[]> wanted = queries.stream().map(q -> normalize(embeddings.embed(q))).toList();
            List<float[]> unwanted = avoid.stream().map(q -> normalize(embeddings.embed(q))).toList();
            Set<Integer> excluded = new java.util.HashSet<>();
            for (int i = 0; i < vectors.size(); i++) {
                if (best(vectors.get(i), unwanted) > best(vectors.get(i), wanted)) {
                    excluded.add(i);
                }
            }
            List<List<Integer>> rankings = wanted.stream()
                    .map(q -> rank(q).stream().filter(i -> !excluded.contains(i)).toList()).toList();
            // tour à tour : le meilleur de chaque question, puis le 2e de chaque question…
            for (int rank = 0; rank < perQuery && chosen.size() < max; rank++) {
                for (List<Integer> ranking : rankings) {
                    if (rank < ranking.size() && chosen.size() < max) {
                        chosen.add(ranking.get(rank));
                    }
                }
            }
            return chosen.stream().sorted().toList();
        }

        /** Les embeddings d'autres textes, avec le même modèle (pour comparer des points entre eux). */
        public List<float[]> embed(List<String> texts) {
            return PassageSearch.this.embed(texts);
        }

        public String passage(int i) {
            return passages.get(i);
        }

        /** La meilleure proximité entre un passage et une liste de recherches (-1 si la liste est vide). */
        private static double best(float[] passage, List<float[]> queries) {
            return queries.stream().mapToDouble(q -> dot(passage, q)).max().orElse(-1);
        }

        private List<Integer> rank(float[] query) {
            double[] scores = vectors.stream().mapToDouble(v -> dot(v, query)).toArray();
            return IntStream.range(0, scores.length).boxed()
                    .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed())
                    .toList();
        }
    }

    /** Ramène la liste à une longueur de 1 : le produit scalaire devient alors le cosinus. */
    static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = norm == 0 ? 0 : (float) (v[i] / norm);
        }
        return out;
    }

    static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
