-- Historique des notes : une ligne par société, profil, jour et origine.
--   FILING   : recalculée à l'arrivée d'un rapport (topic Kafka « filings ») ;
--   DAILY    : photo quotidienne après la clôture (la note Value suit le cours) ;
--   BACKFILL : reconstituée pour chaque exercice passé, à sa date de clôture.
-- La clé primaire rend l'écriture idempotente : rejouer un message réécrit la même ligne
-- (INSERT … ON CONFLICT DO UPDATE) au lieu d'en ajouter une.
CREATE TABLE score_history (
    company_id      BIGINT      NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    profile         VARCHAR(20) NOT NULL,
    day             DATE        NOT NULL,
    origin          VARCHAR(10) NOT NULL,
    score           SMALLINT    NOT NULL,
    fiscal_year_end DATE,
    source_id       TEXT,
    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (company_id, profile, day, origin)
);
