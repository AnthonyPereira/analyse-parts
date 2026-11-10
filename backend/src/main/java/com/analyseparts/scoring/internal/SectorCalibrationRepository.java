package com.analyseparts.scoring.internal;

import com.analyseparts.scoring.core.Ratio;
import com.analyseparts.scoring.core.SectorCalibration;
import com.analyseparts.scoring.core.SectorCalibration.Median;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class SectorCalibrationRepository {

    private final JdbcTemplate jdbc;

    public SectorCalibrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Remplace la calibration précédente (en une transaction : jamais de calibration à moitié écrite). */
    @Transactional
    public void replace(SectorCalibration calibration) {
        Instant at = calibration.getCalibratedAt().orElseThrow();
        jdbc.update("DELETE FROM sector_calibration");
        jdbc.batchUpdate("""
                INSERT INTO sector_calibration (level, code, label, ratio, median, sample_size, calibrated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, calibration.getMedians(), 200, (ps, m) -> {
            ps.setString(1, m.level());
            ps.setString(2, m.code());
            ps.setString(3, m.label());
            ps.setString(4, m.ratio().name());
            ps.setDouble(5, m.median());
            ps.setInt(6, m.sampleSize());
            ps.setTimestamp(7, Timestamp.from(at));
        });
    }

    public SectorCalibration load() {
        List<Median> medians = new ArrayList<>();
        Instant[] at = {null};
        jdbc.query("SELECT level, code, label, ratio, median, sample_size, calibrated_at FROM sector_calibration", rs -> {
            String ratio = rs.getString("ratio");
            // un ratio retiré du code depuis le calibrage est ignoré
            if (java.util.Arrays.stream(Ratio.values()).anyMatch(r -> r.name().equals(ratio))) {
                medians.add(new Median(rs.getString("level"), rs.getString("code"), rs.getString("label"),
                        Ratio.valueOf(ratio), rs.getDouble("median"), rs.getInt("sample_size")));
            }
            at[0] = rs.getTimestamp("calibrated_at").toInstant();
        });
        return medians.isEmpty() ? SectorCalibration.createEmpty() : SectorCalibration.of(medians, at[0]);
    }
}
