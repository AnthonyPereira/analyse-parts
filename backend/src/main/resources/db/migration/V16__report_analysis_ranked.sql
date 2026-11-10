-- Points de chaque thème classés par importance (ADR 0018) : date du classement, vide si les points
-- sont encore dans l'ordre de lecture (lectures faites avant le 10 novembre 2026).
ALTER TABLE report_analysis ADD COLUMN ranked_at timestamptz;
