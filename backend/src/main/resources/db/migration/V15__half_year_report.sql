-- Rapports financiers semestriels (PDF) lus par le modèle de langage local (ADR 0015).
-- Une ligne par société et par semestre ; content : les lignes vérifiées du compte de résultat, en
-- JSON (montants en euros, BPA en euros par action). Affiché à côté de la note, jamais dans son calcul.
CREATE TABLE half_year_report (
    company_id   BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    period_end   DATE         NOT NULL,
    published_on DATE         NOT NULL,
    title        TEXT,
    source_url   TEXT         NOT NULL,
    model        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    content      JSONB        NOT NULL,
    PRIMARY KEY (company_id, period_end)
);
