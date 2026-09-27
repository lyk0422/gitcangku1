package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 放行历史持久化。历史只增不改，证书撤销后保留；
 * 放行时写入不确定度预算快照（分量、计算口径、阈值版本），后续阈值修订不改写。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> MAPPER = (rs, rowNum) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getLong("measurement_id"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getBigDecimal("combined_uncertainty"),
            rs.getInt("threshold_version"),
            rs.getBigDecimal("threshold_value"),
            rs.getInt("budget_version"),
            rs.getString("calc_spec"),
            rs.getString("components_snapshot"));

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放行历史（含不确定度预算快照）。
     */
    public void insert(String batchId, long measurementId, String releasedBy, Instant releasedAt,
                       BigDecimal combinedUncertainty, int thresholdVersion, BigDecimal thresholdValue,
                       int budgetVersion, String calcSpec, String componentsSnapshot) {
        jdbc.update("INSERT INTO release_record (batch_id, measurement_id, released_by, released_at, "
                        + "combined_uncertainty, threshold_version, threshold_value, budget_version, "
                        + "calc_spec, components_snapshot) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchId, measurementId, releasedBy, JdbcTimes.toDb(releasedAt),
                combinedUncertainty, thresholdVersion, thresholdValue, budgetVersion,
                calcSpec, componentsSnapshot);
    }

    /**
     * 查询某测量记录的全部放行历史（按时间升序）。
     */
    public List<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM release_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }
}
