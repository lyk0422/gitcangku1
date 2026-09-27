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
 * 放行时的预算版本、合成不确定度与阈值快照一并固化，后续修订不回写。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> MAPPER = (rs, rowNum) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getLong("measurement_id"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getLong("budget_version"),
            rs.getBigDecimal("combined_uncertainty"),
            rs.getLong("threshold_version_id"),
            rs.getBigDecimal("threshold_value"),
            rs.getString("calculation_method"));

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放行历史（含预算版本、合成不确定度与阈值快照）。
     */
    public void insert(String batchId, long measurementId, String releasedBy, Instant releasedAt,
                       long budgetVersion, BigDecimal combinedUncertainty,
                       long thresholdVersionId, BigDecimal thresholdValue, String calculationMethod) {
        jdbc.update("INSERT INTO release_record "
                        + "(batch_id, measurement_id, released_by, released_at, budget_version, "
                        + "combined_uncertainty, threshold_version_id, threshold_value, calculation_method) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchId, measurementId, releasedBy, JdbcTimes.toDb(releasedAt),
                budgetVersion, combinedUncertainty, thresholdVersionId, thresholdValue, calculationMethod);
    }

    /**
     * 查询某测量记录的全部放行历史（按时间升序，以自增主键稳定排序）。
     */
    public List<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM release_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }
}
