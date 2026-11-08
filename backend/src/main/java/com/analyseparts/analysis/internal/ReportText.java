package com.analyseparts.analysis.internal;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Découpe le texte d'un rapport annuel XHTML en <b>passages</b> d'environ 1 000 à 1 800 caractères :
 * assez courts pour que la recherche retrouve le bon endroit, assez longs pour garder le sens.
 * <p>
 * Ce qui est écarté :
 * <ul>
 *   <li>{@code <head>}, {@code <style>}, {@code <script>} et l'en-tête caché {@code ix:header} ;</li>
 *   <li>les cellules de tableau courtes ou chiffrées (états financiers, sommaires). Les paragraphes
 *       rédigés dans un tableau sont gardés, avec leur intitulé ; et tout le tableau l'est si le
 *       rapport met presque tout son texte dans des tableaux, ce que font certains convertisseurs
 *       de PDF ;</li>
 *   <li>les passages surtout faits de chiffres.</li>
 * </ul>
 * Les rapports sont souvent des PDF convertis : un mot peut être coupé entre plusieurs
 * {@code <span>}. On recolle donc le texte à l'intérieur d'un bloc, et on ne coupe qu'entre blocs.
 */
public final class ReportText {

    static final int TARGET = 1000;
    static final int MAX = 1800;

    private static final Set<String> SKIPPED = Set.of("head", "style", "script", "header");
    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
            "tr", "td", "th", "br", "section", "article", "ul", "ol", "blockquote", "dt", "dd", "table");

    private ReportText() {
    }

    /** Un bloc de texte, et s'il vient d'un tableau. */
    record Block(String text, boolean inTable) {
    }

    public static List<String> passages(InputStream xhtml) throws IOException {
        List<Block> blocks = blocks(xhtml);
        long all = blocks.stream().mapToLong(b -> b.text().length()).sum();
        long outside = blocks.stream().filter(b -> !b.inTable()).mapToLong(b -> b.text().length()).sum();
        boolean keepTables = all > 0 && outside < all * 0.3;
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (keepTables || !b.inTable() || prose(b) || label(b) && proseNear(blocks, i)) {
                texts.add(b.text());
            }
        }
        return chunk(texts);
    }

    /**
     * Un paragraphe rédigé, même dans un tableau : certains rapports présentent leurs facteurs de
     * risque en tableau (« Identification du risque » | « Gestion du risque »). Les cellules de
     * chiffres, elles, sont courtes.
     */
    static boolean prose(Block b) {
        return b.text().length() >= 150 && letters(b.text()) >= b.text().length() * 0.6;
    }

    /** Un paragraphe de tableau juste avant, ou dans les trois blocs qui suivent (après l'en-tête de colonnes). */
    private static boolean proseNear(List<Block> blocks, int i) {
        for (int j = i - 1; j <= i + 3; j++) {
            if (j != i && j >= 0 && j < blocks.size() && blocks.get(j).inTable() && prose(blocks.get(j))) {
                return true;
            }
        }
        return false;
    }

    /** Un intitulé court (« Risques liés à l'activité/Crise sanitaire ») : gardé à côté d'un paragraphe. */
    private static boolean label(Block b) {
        return b.text().length() >= 15 && letters(b.text()) >= b.text().length() * 0.7;
    }

    private static long letters(String s) {
        return s.chars().filter(Character::isLetter).count();
    }

    static List<Block> blocks(InputStream xhtml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        List<Block> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int skipped = 0;
        int tables = 0;
        try {
            XMLStreamReader r = factory.createXMLStreamReader(xhtml);
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName().toLowerCase();
                    boolean start = event == XMLStreamConstants.START_ELEMENT;
                    if (SKIPPED.contains(name)) {
                        skipped += start ? 1 : -1;
                    } else if (BLOCKS.contains(name)) {
                        flush(current, blocks, tables > 0);
                        if (name.equals("table")) {
                            tables += start ? 1 : -1;
                        }
                    }
                } else if (skipped == 0 && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                    current.append(r.getText());
                }
            }
            flush(current, blocks, tables > 0);
        } catch (XMLStreamException e) {
            throw new IOException("Rapport XHTML illisible : " + e.getMessage(), e);
        }
        return blocks;
    }

    private static void flush(StringBuilder current, List<Block> blocks, boolean inTable) {
        String text = clean(current);
        current.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(new Block(text, inTable));
        }
    }

    /** Espaces insécables et retours à la ligne ramenés à un seul espace. */
    static String clean(CharSequence s) {
        return s.toString().replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    /** Regroupe les blocs en passages ; un bloc trop long est coupé entre deux phrases. */
    static List<String> chunk(List<String> texts) {
        List<String> passages = new ArrayList<>();
        StringBuilder passage = new StringBuilder();
        for (String text : texts) {
            for (String piece : split(text)) {
                if (!passage.isEmpty() && passage.length() + 1 + piece.length() > MAX) {
                    keep(passage, passages);
                }
                if (!passage.isEmpty()) {
                    passage.append(' ');
                }
                passage.append(piece);
                if (passage.length() >= TARGET) {
                    keep(passage, passages);
                }
            }
        }
        keep(passage, passages);
        return passages;
    }

    private static List<String> split(String text) {
        if (text.length() <= MAX) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?;])\\s+")) {
            if (!piece.isEmpty() && piece.length() + 1 + sentence.length() > MAX) {
                pieces.add(piece.toString());
                piece.setLength(0);
            }
            if (!piece.isEmpty()) {
                piece.append(' ');
            }
            // une « phrase » sans ponctuation plus longue que MAX : coupée net
            while (sentence.length() > MAX) {
                pieces.add(sentence.substring(0, MAX));
                sentence = sentence.substring(MAX);
            }
            piece.append(sentence);
        }
        if (!piece.isEmpty()) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /** Garde le passage s'il est fait de texte plus que de chiffres. */
    private static void keep(StringBuilder passage, List<String> passages) {
        String text = passage.toString();
        passage.setLength(0);
        long letters = text.chars().filter(Character::isLetter).count();
        if (text.length() >= 80 && letters >= text.length() * 0.5) {
            passages.add(text);
        }
    }
}
