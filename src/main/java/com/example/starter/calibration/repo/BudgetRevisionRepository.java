package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BudgetRevision;

/**
 * 不确定度预算修订历史持久化。历史只增不改，按测量版本升序查询。
 */
@Repository
public class BudgetRevisionRepository {

    private static final RowMapper<BudgetRevision> MAPPER = (rs, rowNum) -> new BudgetRevision(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getInt("measurement_version"),
            rs.getBigDecimal("combined_uncertainty"),
            rs.getInt("threshold_version"),
            rs.getString("components_snapshot"),
            rs.getString("changed_by"),
            rs.getString("request_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BudgetRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条预算修订历史。
     */
    public void insert(long measurementId, int measurementVersion, BigDecimal combinedUncertainty,
                       int thresholdVersion, String componentsSnapshot, String changedBy,
                       String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO uncertainty_budget_revision "
                        + "(measurement_id, measurement_version, combined_uncertainty, threshold_version, "
                        + "components_snapshot, changed_by, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                measurementId, measurementVersion, combinedUncertainty, thresholdVersion,
                componentsSnapshot, changedBy, requestId, JdbcTimes.toDb(createdAt));
    }

    /**
     * 查询某测量的全部预算修订历史（按测量版本升序，保证稳定排序）。
     */
    public List<BudgetRevision> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM uncertainty_budget_revision WHERE measurement_id = ? "
                        + "ORDER BY measurement_version", MAPPER, measurementId);
    }
}
