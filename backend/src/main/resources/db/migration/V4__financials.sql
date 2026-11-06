-- Rapport annuel ESEF importé (une ligne par rapport retenu, source : filings.xbrl.org)
CREATE TABLE filing (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT       NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    source_id    VARCHAR(200) NOT NULL UNIQUE,   -- identifiant filings.xbrl.org (fxo_id)
    period_end   DATE         NOT NULL,          -- date de clôture de l'exercice
    country      VARCHAR(2),                     -- pays de dépôt (FR, GB… : souvent la langue)
    json_url     TEXT         NOT NULL,
    error_count  INT,                            -- erreurs de validation XBRL signalées par la source
    fact_count   INT          NOT NULL,
    imported_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX filing_company_idx ON filing (company_id);

-- Faits chiffrés bruts d'un rapport : uniquement les totaux (faits sans axe de ventilation).
-- Les indicateurs (chiffre d'affaires, résultat net…) sont calculés à partir de ces faits,
-- ce qui permet d'améliorer le calcul sans retélécharger les rapports. Voir ADR 0002.
CREATE TABLE reported_fact (
    id            BIGSERIAL PRIMARY KEY,
    filing_id     BIGINT       NOT NULL REFERENCES filing(id) ON DELETE CASCADE,
    concept       VARCHAR(255) NOT NULL,   -- ex. ifrs-full:Revenue
    period_start  DATE,                    -- NULL pour une valeur à une date (bilan)
    period_end    DATE         NOT NULL,
    unit          VARCHAR(100) NOT NULL,   -- ex. iso4217:EUR, iso4217:EUR/xbrli:shares
    value         NUMERIC      NOT NULL,
    decimals      INT
);
CREATE INDEX reported_fact_filing_idx ON reported_fact (filing_id);
