package com.analyseparts.scoring.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Barème qui convertit la valeur d'un ratio en points (0 à 100).
 * <p>
 * Le barème est une suite de points d'ancrage (valeur → points) ; entre deux ancrages, les
 * points varient linéairement ; en dehors, on prend les points de l'ancrage le plus proche.
 * Exemple pour la marge nette : {@code 0 % → 0, 5 % → 40, 10 % → 70, 20 % → 100} :
 * une marge de 7,5 % vaut 55 points, une marge de 30 % vaut 100.
 * <p>
 * Les ancrages peuvent monter (plus c'est haut, mieux c'est), descendre (endettement) ou
 * former une cloche (taux de distribution : ni trop bas, ni trop haut).
 */
public record Scale(List<Anchor> anchors) {

    public record Anchor(double value, double points) {
    }

    public Scale {
        if (anchors.size() < 2) {
            throw new IllegalArgumentException("Un barème a au moins deux ancrages");
        }
        for (int i = 1; i < anchors.size(); i++) {
            if (anchors.get(i).value() <= anchors.get(i - 1).value()) {
                throw new IllegalArgumentException("Les ancrages doivent être triés par valeur croissante");
            }
        }
        anchors = List.copyOf(anchors);
    }

    /** {@code Scale.of(0, 0, 0.05, 40, 0.10, 70)} : paires (valeur, points). */
    public static Scale of(double... valuePointPairs) {
        if (valuePointPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Il faut des paires (valeur, points)");
        }
        List<Anchor> anchors = new ArrayList<>();
        for (int i = 0; i < valuePointPairs.length; i += 2) {
            anchors.add(new Anchor(valuePointPairs[i], valuePointPairs[i + 1]));
        }
        return new Scale(anchors);
    }

    public double points(double value) {
        Anchor first = anchors.getFirst();
        Anchor last = anchors.getLast();
        if (value <= first.value()) {
            return first.points();
        }
        if (value >= last.value()) {
            return last.points();
        }
        for (int i = 1; i < anchors.size(); i++) {
            Anchor hi = anchors.get(i);
            if (value <= hi.value()) {
                Anchor lo = anchors.get(i - 1);
                double t = (value - lo.value()) / (hi.value() - lo.value());
                return lo.points() + t * (hi.points() - lo.points());
            }
        }
        return last.points();
    }
}
