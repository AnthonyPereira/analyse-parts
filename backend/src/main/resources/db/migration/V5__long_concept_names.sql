-- Les concepts propres à une société (extensions de taxonomie, ex. "LVM:…") peuvent
-- dépasser 255 caractères.
ALTER TABLE reported_fact ALTER COLUMN concept TYPE TEXT;
