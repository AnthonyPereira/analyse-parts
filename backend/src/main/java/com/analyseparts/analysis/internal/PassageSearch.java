package com.analyseparts.analysis.internal;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Chaque passage est transformé par le modèle {@code snowflake-arctic-embed2} en <b>embedding</b> :
 * une liste de 1 024 nombres qui résume son sens. Deux textes de sens proche ont des embeddings
 * proches ; on mesure cette proximité par le <b>cosinus</b> de l'angle entre les deux listes
 * (1 = même sens).
 * <p>
 * Les <b>recherches</b> (pas les passages) sont précédées de « query: » : le modèle a été entraîné
 * ainsi, et il trouve alors plus de passages utiles (ADR 0017).
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
    private final String queryPrefix;

    /**
     * @param queryPrefix texte ajouté devant chaque recherche ; propre au modèle d'embedding
     *                    (« query: » pour snowflake-arctic-embed2, rien pour bge-m3)
     */
    @Autowired
    public PassageSearch(EmbeddingModel embeddings,
                         @Value("${analyse-parts.analysis.query-prefix:query: }") String queryPrefix) {
        this.embeddings = embeddings;
        this.queryPrefix = queryPrefix;
    }

    /** Sans préfixe : pour les tests, dont les faux embeddings ne dépendent que du texte. */
    PassageSearch(EmbeddingModel embeddings) {
        this(embeddings, "");
    }

    /** Les passages d'un rapport, avec leurs embeddings, prêts à être interrogés. */
    public Index buildIndex(List<String> passages) {
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

        public int countPassages() {
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
            return search(queries, avoid, List.of(), perQuery, max);
        }

        /** Pareil, en complétant des passages déjà choisis ({@code already}, rendus avec les autres). */
        public List<Integer> search(List<String> queries, List<String> avoid, List<Integer> already, int perQuery, int max) {
            Set<Integer> chosen = new LinkedHashSet<>(already);
            List<float[]> wanted = embedQueries(queries);
            Set<Integer> excluded = findExcluded(wanted, embedQueries(avoid));
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

        /**
         * Un <b>chapitre</b> entier plutôt que des passages épars : les {@code length} passages
         * qui se suivent et qui, <b>en moyenne</b>, sont les plus proches des {@code queries}.
         * <p>
         * Un rapport décrit son activité dans un chapitre d'un seul tenant (chez L'Oréal, les
         * passages 11 à 75 sur 1 238). La recherche passage par passage y mêle la lettre du président
         * ou le développement durable, qui parlent aussi d'activité, et chercher le seul titre du
         * chapitre ne marche pas : le titre est fondu dans un passage qui parle d'autre chose. Une
         * moyenne sur plusieurs passages voisins, elle, repère le chapitre. Un passage écarté par
         * {@code avoid} compte comme le moins proche de tous. Rend les numéros dans l'ordre du rapport.
         * <p>
         * {@code within} : la part du rapport où le chapitre doit commencer (1 : n'importe où). La
         * présentation du groupe ouvre le document d'enregistrement universel (passages 3 à 53 chez
         * Nexans, L'Oréal et Covivio) ; sans limite, chez BNP Paribas, l'information sectorielle des
         * notes annexes, au passage 1 775 sur 2 067, l'emportait.
         */
        public List<Integer> findChapter(List<String> queries, List<String> avoid, int length, double within) {
            if (queries.isEmpty() || vectors.isEmpty()) {
                return List.of();
            }
            List<float[]> wanted = embedQueries(queries);
            Set<Integer> excluded = findExcluded(wanted, embedQueries(avoid));
            int end = Math.max(Math.min(length, vectors.size()), (int) Math.ceil(vectors.size() * within));
            double[] score = new double[Math.min(vectors.size(), end + length - 1)];
            for (int i = 0; i < score.length; i++) {
                score[i] = excluded.contains(i) ? -1 : computeBestSimilarity(vectors.get(i), wanted);
            }
            int start = findDensestStart(score, length);
            return IntStream.range(start, Math.min(score.length, start + length)).boxed().toList();
        }

        public List<Integer> findChapter(List<String> queries, List<String> avoid, int length) {
            return findChapter(queries, avoid, length, 1);
        }

        /** Le début de la suite de {@code length} cases dont la somme est la plus grande. */
        static int findDensestStart(double[] score, int length) {
            int n = Math.min(length, score.length);
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += score[i];
            }
            double bestSum = sum;
            int bestStart = 0;
            for (int start = 1; start + n <= score.length; start++) {
                sum += score[start + n - 1] - score[start - 1];
                if (sum > bestSum) {
                    bestSum = sum;
                    bestStart = start;
                }
            }
            return bestStart;
        }

        private List<float[]> embedQueries(List<String> queries) {
            return queries.stream().map(q -> normalize(embeddings.embed(queryPrefix + q))).toList();
        }

        /** Les passages plus proches d'une recherche à éviter que de toutes les recherches voulues. */
        private Set<Integer> findExcluded(List<float[]> wanted, List<float[]> unwanted) {
            Set<Integer> excluded = new java.util.HashSet<>();
            for (int i = 0; i < vectors.size(); i++) {
                if (isContents(passages.get(i)) || computeBestSimilarity(vectors.get(i), unwanted) > computeBestSimilarity(vectors.get(i), wanted)) {
                    excluded.add(i);
                }
            }
            return excluded;
        }

        /** Un intitulé numéroté : « 1.3.2 Autres activités », « 3.5Facteurs de risques ». */
        private static final java.util.regex.Pattern HEADING =
                java.util.regex.Pattern.compile("(?<![\\d.,])\\d{1,2}(\\.\\d{1,2}){1,3}\\.?\\s*\\p{Lu}");

        /** Intitulés numérotés à partir desquels un passage est un sommaire. */
        static final int CONTENTS_HEADINGS = 6;

        /**
         * Un <b>sommaire</b> : une suite d'intitulés numérotés. Sa proximité avec n'importe quelle
         * recherche est trompeuse (il cite tous les sujets) et il ne dit rien : chez Nexans, le
         * chapitre de présentation lu commençait par le sommaire, et le modèle en tirait des titres.
         * Un vrai chapitre n'a qu'un ou deux intitulés par passage.
         */
        static boolean isContents(String passage) {
            var m = HEADING.matcher(passage);
            int headings = 0;
            while (m.find() && ++headings < CONTENTS_HEADINGS) {
                // compte jusqu'au seuil
            }
            return headings >= CONTENTS_HEADINGS;
        }

        /** Les embeddings d'autres textes, avec le même modèle (pour comparer des points entre eux). */
        public List<float[]> embed(List<String> texts) {
            return PassageSearch.this.embed(texts);
        }

        public String getPassage(int i) {
            return passages.get(i);
        }

        /** La meilleure proximité entre un passage et une liste de recherches (-1 si la liste est vide). */
        private static double computeBestSimilarity(float[] passage, List<float[]> queries) {
            return queries.stream().mapToDouble(q -> computeDotProduct(passage, q)).max().orElse(-1);
        }

        private List<Integer> rank(float[] query) {
            double[] scores = vectors.stream().mapToDouble(v -> computeDotProduct(v, query)).toArray();
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

    static double computeDotProduct(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
