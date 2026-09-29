package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.UncertaintyMethod;

/**
 * 放行历史持久化。历史只增不改，证书撤销后保留；
 * 每行同时固化当次放行读取的预算与阈值版本快照，后续阈值修订不改写历史。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> MAPPER = (rs, rowNum) -> {
        String method = rs.getString("calc_method");
        return new ReleaseRecord(
                rs.getLong("id"),
                rs.getString("batch_id"),
                rs.getLong("measurement_id"),
                rs.getString("released_by"),
                JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
                (Long) rs.getObject("budget_id"),
                method == null ? null : UncertaintyMethod.valueOf(method),
                rs.getBigDecimal("combined_uncertainty"),
                (Long) rs.getObject("threshold_id"),
                (Integer) rs.getObject("threshold_version"),
                rs.getBigDecimal("threshold_value"));
    };

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放行历史并固化预算/阈值快照。快照字段可为 null（测量无预算且无阈值）。
     */
    public void insert(ReleaseSnapshot snapshot) {
        jdbc.update("INSERT INTO release_record "
                        + "(batch_id, measurement_id, released_by, released_at, budget_id, calc_method, "
                        + "combined_uncertainty, threshold_id, threshold_version, threshold_value) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.batchId(), snapshot.measurementId(), snapshot.releasedBy(),
                JdbcTimes.toDb(snapshot.releasedAt()),
                snapshot.budgetId(),
                snapshot.method() == null ? null : snapshot.method().name(),
                snapshot.combinedUncertainty(),
                snapshot.thresholdId(),
                snapshot.thresholdVersion(),
                snapshot.thresholdValue());
    }

    /**
     * 查询某测量记录的全部放行历史（按写入先后稳定升序）。
     */
    public List<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM release_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }

    /**
     * 放行写入参数：业务字段加预算/阈值快照。
     */
    public record ReleaseSnapshot(
            String batchId,
            long measurementId,
            String releasedBy,
            Instant releasedAt,
            Long budgetId,
            UncertaintyMethod method,
            java.math.BigDecimal combinedUncertainty,
            Long thresholdId,
            Integer thresholdVersion,
            java.math.BigDecimal thresholdValue) {
    }
}
