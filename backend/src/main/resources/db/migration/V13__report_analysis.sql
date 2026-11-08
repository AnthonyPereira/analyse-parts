-- Lecture des rapports annuels par le modèle de langage local (module analysis, ADR 0011).
-- Une ligne par rapport lu ; la page d'une société affiche la plus récente.
-- content : les thèmes et leurs points, en JSON (record ReportAnalysis sans ses métadonnées).
-- Ces textes sont affichés à côté de la note et n'entrent jamais dans son calcul.
CREATE TABLE report_analysis (
    company_id    BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    source_id     TEXT         NOT NULL,
    period_end    DATE         NOT NULL,
    model         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms   BIGINT       NOT NULL,
    passage_count INT          NOT NULL,
    rejected      INT          NOT NULL,
    content       JSONB        NOT NULL,
    PRIMARY KEY (company_id, source_id)
);
