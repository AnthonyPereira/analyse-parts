package com.analyseparts.scoring.core;

/** Grandes questions auxquelles répondent les ratios. */
public enum RatioFamily {
    PROFITABILITY("Rentabilité", "L'entreprise gagne-t-elle bien sa vie ?"),
    SOLIDITY("Solidité financière", "Peut-elle encaisser un coup dur ?"),
    CASH("Qualité du cash", "Ses bénéfices se transforment-ils en argent réel ?"),
    GROWTH("Croissance", "Grandit-elle ?"),
    REGULARITY("Régularité", "Ses résultats sont-ils fiables dans le temps ?"),
    DIVIDEND("Dividende", "Partage-t-elle ses bénéfices de façon soutenable ?");

    private final String label;
    private final String question;

    RatioFamily(String label, String question) {
        this.label = label;
        this.question = question;
    }

    public String label() {
        return label;
    }

    public String question() {
        return question;
    }
}
