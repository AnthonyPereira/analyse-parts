-- L'index filings.xbrl.org déclare parfois une mauvaise date de clôture (ex. rapports 2025
-- de Carrefour, Hermès et Rubis déclarés au 31/12/2026). On la recalcule à partir des faits
-- du rapport : la fin la plus tardive des périodes de flux. Les écarts de 7 jours ou moins
-- (façon d'écrire la fin de période) sont ignorés. Même règle que EsefImportService.periodEnd.
UPDATE filing f
SET period_end = t.max_end
FROM (SELECT filing_id, max(period_end) AS max_end
      FROM reported_fact
      WHERE period_start IS NOT NULL
      GROUP BY filing_id) t
WHERE t.filing_id = f.id
  AND abs(t.max_end - f.period_end) > 7;
