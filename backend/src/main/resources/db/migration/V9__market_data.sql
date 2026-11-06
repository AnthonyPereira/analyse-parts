-- Dernier cours connu de chaque société (l'historique viendra avec le backtest, Phase 6).
CREATE TABLE quote (
    company_id BIGINT PRIMARY KEY REFERENCES company (id) ON DELETE CASCADE,
    price      NUMERIC(19, 6) NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    -- heure du cours à la source (pas l'heure de son enregistrement)
    as_of      TIMESTAMPTZ    NOT NULL,
    -- YAHOO (en séance, ~15 min de différé) ou EURONEXT_CLOSE (clôture de la veille)
    source     VARCHAR(20)    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Taux de change de référence de la BCE : combien de <currency> vaut 1 euro.
CREATE TABLE fx_rate (
    currency     VARCHAR(3) PRIMARY KEY,
    rate_per_eur NUMERIC(19, 8) NOT NULL,
    as_of        DATE           NOT NULL
);
