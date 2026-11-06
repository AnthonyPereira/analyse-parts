-- Fiche Euronext de la société : classification sectorielle ICB et nombre d'actions admises.
-- La colonne "sector" (jamais remplie) est remplacée par les 4 niveaux ICB.
ALTER TABLE company DROP COLUMN sector;
ALTER TABLE company
    ADD COLUMN mic                  VARCHAR(4) NOT NULL DEFAULT 'XPAR',
    -- Code ICB du sous-secteur (8 chiffres) : ses 2, 4 et 6 premiers chiffres donnent
    -- l'industrie, le super-secteur et le secteur.
    ADD COLUMN icb_code             VARCHAR(8),
    ADD COLUMN icb_industry         VARCHAR(100),
    ADD COLUMN icb_supersector      VARCHAR(100),
    ADD COLUMN icb_sector           VARCHAR(100),
    ADD COLUMN icb_subsector        VARCHAR(100),
    ADD COLUMN shares_admitted      BIGINT,
    ADD COLUMN factsheet_checked_at TIMESTAMPTZ;
