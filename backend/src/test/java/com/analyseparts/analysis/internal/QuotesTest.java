package com.analyseparts.analysis.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuotesTest {

    static final List<String> PASSAGES = List.of(
            "Le Groupe réalise 38 % de son chiffre d’affaires en Europe. La concurrence s’intensifie en Chine.",
            "L’Oréal est le numéro un mondial de la beauté, avec 37 marques internationales et 4 000 brevets.");

    @Test
    void aVerbatimQuoteIsFoundAndTheOriginalTextReturned() {
        var match = Quotes.find("l'Oréal est le numéro un mondial de la beauté", PASSAGES, 1);
        assertThat(match).isPresent();
        assertThat(match.get().passage()).isEqualTo(1);
        // apostrophe typographique et majuscule du rapport, pas celles du modèle
        assertThat(match.get().text()).isEqualTo("L’Oréal est le numéro un mondial de la beauté");
    }

    @Test
    void spacingQuotesAccentsAndFinalPunctuationAreTolerated() {
        assertThat(Quotes.find("« La  concurrence s'intensifie en chine. »", PASSAGES, 0)).isPresent();
        assertThat(Quotes.find("La concurrence s'intensifie en Chine", PASSAGES, 0)).isPresent();
    }

    @Test
    void aWrongPassageNumberIsCorrected() {
        var match = Quotes.find("La concurrence s’intensifie en Chine", PASSAGES, 1);
        assertThat(match).isPresent();
        assertThat(match.get().passage()).isZero();
    }

    @Test
    void anEllipsisNeedsEveryFragmentInOrder() {
        assertThat(Quotes.find("L’Oréal est le numéro un … 4 000 brevets", PASSAGES, 1).get().text())
                .isEqualTo("L’Oréal est le numéro un mondial de la beauté, avec 37 marques internationales et 4 000 brevets");
        assertThat(Quotes.find("4 000 brevets ... L’Oréal est le numéro un", PASSAGES, 1)).isEmpty();
    }

    @Test
    void anInventedOrRewordedQuoteIsRejected() {
        assertThat(Quotes.find("L'Oréal est le leader mondial incontesté de la beauté", PASSAGES, 1)).isEmpty();
        assertThat(Quotes.find("Le Groupe réalise 45 % de son chiffre d'affaires en Europe", PASSAGES, 0)).isEmpty();
    }

    /** Vu sur le rapport de L'Oréal : le modèle recopie le numéro de l'extrait et la puce de liste. */
    @Test
    void anExtractNumberAndBulletsCopiedByTheModelAreIgnored() {
        List<String> passages = List.of("Trois divisions : ●la Division Luxe a pour mission de créer l'avenir de la beauté de luxe.");
        var match = Quotes.find("[8] ●la Division Luxe a pour mission de créer l'avenir de la beauté de luxe.", passages, 7);
        assertThat(match).isPresent();
        // la puce n'est pas reprise dans la citation affichée
        assertThat(match.get().text()).isEqualTo("la Division Luxe a pour mission de créer l'avenir de la beauté de luxe");
        assertThat(Quotes.find("la Division Luxe a pour mission de créer l'avenir", passages, 0)).isPresent();
    }

    @Test
    void aDamagedNumberIsRejected() {
        List<String> passages = List.of("Le chiffre d'affaires s'élève à 44,05 milliards d'euros au 31 décembre 2025.");
        assertThat(Quotes.find("Le chiffre d'affaires s'élève à 44,05 milliards d'euros au 3,1 décembre 2025", passages, 0)).isEmpty();
    }

    @Test
    void aTooShortQuoteIsRejected() {
        assertThat(Quotes.find("numéro un mondial", PASSAGES, 1)).isEmpty();
        assertThat(Quotes.find(null, PASSAGES, 1)).isEmpty();
    }
}
