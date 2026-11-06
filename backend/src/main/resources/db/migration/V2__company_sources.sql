-- Données venant des sources externes :
--  market         : place(s) de cotation selon Euronext (ex. "Euronext Paris")
--  legal_name     : raison sociale officielle selon GLEIF
--  lei_checked_at : date de la dernière recherche GLEIF, pour ne pas redemander
--                   en boucle les sociétés pour lesquelles GLEIF ne trouve rien
ALTER TABLE company ADD COLUMN market         VARCHAR(100);
ALTER TABLE company ADD COLUMN legal_name     VARCHAR(255);
ALTER TABLE company ADD COLUMN lei_checked_at TIMESTAMPTZ;
