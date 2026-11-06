-- Société cotée. L'ISIN identifie le titre, le LEI identifie l'entité juridique
-- (c'est lui qui relie la société à ses rapports ESEF sur filings.xbrl.org).
CREATE TABLE company (
    id          BIGSERIAL PRIMARY KEY,
    isin        VARCHAR(12)  NOT NULL UNIQUE,
    lei         VARCHAR(20)  UNIQUE,
    name        VARCHAR(255) NOT NULL,
    ticker      VARCHAR(20),
    country     CHAR(2)      NOT NULL,
    sector      VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Appartenance à un indice (SBF 120 pour la V1, d'autres indices européens ensuite)
CREATE TABLE index_membership (
    company_id  BIGINT      NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    index_code  VARCHAR(20) NOT NULL,
    PRIMARY KEY (company_id, index_code)
);
