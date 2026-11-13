-- Lignes techniques d'Euronext Amsterdam importées comme des sociétés (actions auto-détenues,
-- lignes de rachat, droits) : sans rapport ni comptes propres, elles n'apparaissaient qu'avec un
-- cours figé de 2021 ou 2023 (revue du 11 novembre 2026). Même règle que le filtre d'import
-- (EuronextEquityCsvParser.NOT_A_SHARE), et seulement pour une ligne sans aucun rapport.
DELETE FROM company c
 WHERE c.name ~* '\m(DSC[0-9]*|TREAS(URY)? SHARES|BUY ?BACK)\M'
   AND NOT EXISTS (SELECT 1 FROM filing f WHERE f.company_id = c.id);
