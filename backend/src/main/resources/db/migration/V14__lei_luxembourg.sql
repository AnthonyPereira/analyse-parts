-- GLEIF ne relie pas l'ISIN de ces trois sociétés luxembourgeoises à leur LEI : la recherche par
-- ISIN revient vide, et elles restaient sans rapport annuel (donc sans score ni lecture).
-- LEI trouvés chez GLEIF par la raison sociale, pays LU (vérifiés le 2026-11-09).
-- esef_checked_at remis à vide : la prochaine collecte ESEF les traite tout de suite.
UPDATE company SET lei = '5493008JPA4HYMH1HX51', legal_name = 'SES', lei_checked_at = now(), esef_checked_at = NULL
WHERE isin = 'LU0088087324' AND lei IS NULL;

UPDATE company SET lei = '2EULGUTUI56JI9SAL165', legal_name = 'ArcelorMittal', lei_checked_at = now(), esef_checked_at = NULL
WHERE isin = 'LU1598757687' AND lei IS NULL;

UPDATE company SET lei = '5493001136GE6L4R7M63', legal_name = 'APERAM', lei_checked_at = now(), esef_checked_at = NULL
WHERE isin = 'LU0569974404' AND lei IS NULL;
