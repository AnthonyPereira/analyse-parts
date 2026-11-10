-- Rapports ESEF trimestriels ou semestriels (ADR 0020) : téléchargés une fois, reconnus à la durée
-- de leurs chiffres (moins de 300 jours) et notés ici pour ne pas être retéléchargés à chaque passage.
CREATE TABLE esef_interim_filing (
    source_id  VARCHAR(200) PRIMARY KEY,
    period_end DATE        NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ceux déjà importés comme des exercices : notés, puis retirés avec leurs faits, les notes calculées
-- sur eux (historique) et leur lecture par le LLM.
INSERT INTO esef_interim_filing (source_id, period_end)
SELECT f.source_id, f.period_end
FROM filing f
WHERE (SELECT max(rf.period_end - rf.period_start)
       FROM reported_fact rf
       WHERE rf.filing_id = f.id AND rf.period_start IS NOT NULL
         AND abs(rf.period_end - f.period_end) <= 7) < 300;

DELETE FROM score_history WHERE source_id IN (SELECT source_id FROM esef_interim_filing);
DELETE FROM report_analysis WHERE source_id IN (SELECT source_id FROM esef_interim_filing);
DELETE FROM filing WHERE source_id IN (SELECT source_id FROM esef_interim_filing);
