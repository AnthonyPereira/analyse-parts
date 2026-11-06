-- Date de la dernière consultation de l'index ESEF pour la société, même si aucun rapport
-- n'a été trouvé : évite de redemander à chaque passage les sociétés sans rapport.
ALTER TABLE company ADD COLUMN esef_checked_at TIMESTAMPTZ;
