-- Médianes des ratios par secteur ICB, figées au moment du calibrage (cf. ADR 0004).
-- level : ALL (toutes les sociétés), SUPERSECTOR ou INDUSTRY ; code : code ICB (ALL pour l'univers).
CREATE TABLE sector_calibration (
    level         VARCHAR(12)      NOT NULL,
    code          VARCHAR(8)       NOT NULL,
    label         VARCHAR(100),
    ratio         VARCHAR(40)      NOT NULL,
    median        DOUBLE PRECISION NOT NULL,
    sample_size   INT              NOT NULL,
    calibrated_at TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (level, code, ratio)
);
