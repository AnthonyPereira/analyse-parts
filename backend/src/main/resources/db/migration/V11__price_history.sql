-- Clôture de la veille : pour afficher la variation du jour (+1,2 %).
ALTER TABLE quote ADD COLUMN previous_close NUMERIC(19, 6);

-- Cours de clôture quotidiens (Yahoo), pour les graphiques et la valorisation passée
-- (PER à chaque date de clôture d'exercice).
CREATE TABLE price_history (
    company_id BIGINT         NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    day        DATE           NOT NULL,
    close      NUMERIC(19, 6) NOT NULL,
    volume     BIGINT,
    PRIMARY KEY (company_id, day)
);
