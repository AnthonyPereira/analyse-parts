-- Émetteurs ESEF hors Euronext déjà examinés (ADR 0020) : leur action a-t-elle été trouvée chez
-- Yahoo ? Évite de refaire, chaque semaine, les recherches des émetteurs sans action cotée
-- (obligations seules) ou sur une place non suivie.
CREATE TABLE esef_issuer_check (
    lei        VARCHAR(20) PRIMARY KEY,
    country    VARCHAR(2)  NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    -- LISTED : société créée ; NOT_LISTED : aucune action sur une place suivie ; ERROR : à refaire
    outcome    VARCHAR(12) NOT NULL,
    isin       VARCHAR(12)
);
