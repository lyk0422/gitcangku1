package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseSnapshot;
import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 放行结论快照持久化。快照与分量明细只追加不改写：阈值修订、预算修改或测量修订均不影响旧快照。
 */
@Repository
public class ReleaseSnapshotRepository {

    private final JdbcTemplate jdbc;

    public ReleaseSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入快照主行并返回自增 ID（分量明细随后写入）。
     */
    public long insert(ReleaseSnapshot snapshot) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_snapshot "
                            + "(batch_id, measurement_id, measurement_key, measurement_version, "
                            + "computed_value, passed_bias, budget_version, combined_uncertainty, formula, "
                            + "threshold_id, threshold_version, threshold_value, uncertainty_ok, decision, "
                            + "released_by, released_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, snapshot.batchId());
            ps.setLong(2, snapshot.measurementId());
            ps.setString(3, snapshot.measurementKey());
            ps.setInt(4, snapshot.measurementVersion());
            ps.setBigDecimal(5, snapshot.computedValue());
            ps.setBoolean(6, snapshot.passedBias());
            ps.setInt(7, snapshot.budgetVersion());
            ps.setBigDecimal(8, snapshot.combinedUncertainty());
            ps.setString(9, snapshot.formula());
            ps.setLong(10, snapshot.thresholdId());
            ps.setInt(11, snapshot.thresholdVersion());
            ps.setBigDecimal(12, snapshot.thresholdValue());
            ps.setBoolean(13, snapshot.uncertaintyOk());
            ps.setString(14, snapshot.decision());
            ps.setString(15, snapshot.releasedBy());
            ps.setObject(16, JdbcTimes.toDb(snapshot.releasedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 追加一条快照分量明细。
     */
    public void insertComponent(long snapshotId, UncertaintyComponent component) {
        jdbc.update("INSERT INTO release_snapshot_component (snapshot_id, name, magnitude) VALUES (?, ?, ?)",
                snapshotId, component.name(), component.magnitude());
    }

    /**
     * 按批次读取全部快照（按快照 ID 升序，即提交顺序，排序稳定）。
     */
    public List<ReleaseSnapshot> findByBatchId(String batchId) {
        return jdbc.query(
                        "SELECT * FROM release_snapshot WHERE batch_id = ? ORDER BY id",
                        (rs, n) -> mapSnapshot(rs), batchId).stream()
                .map(this::withComponents)
                .toList();
    }

    /**
     * 按测量记录（版本行）ID 读取全部快照（按放行时间、快照 ID 升序）。
     */
    public List<ReleaseSnapshot> findByMeasurementId(long measurementId) {
        return jdbc.query(
                        "SELECT * FROM release_snapshot WHERE measurement_id = ? ORDER BY released_at, id",
                        (rs, n) -> mapSnapshot(rs), measurementId).stream()
                .map(this::withComponents)
                .toList();
    }

    /**
     * 按测量业务键读取全部版本的放行快照历史（按放行时间、快照 ID 升序）。
     */
    public List<ReleaseSnapshot> findByMeasurementKey(String measurementKey) {
        return jdbc.query(
                        "SELECT * FROM release_snapshot WHERE measurement_key = ? ORDER BY released_at, id",
                        (rs, n) -> mapSnapshot(rs), measurementKey).stream()
                .map(this::withComponents)
                .toList();
    }

    /**
     * 诊断查询：按时间区间 [from, to) 左闭右开读取快照，按放行时间、快照 ID 升序；查询不修改状态。
     */
    public List<ReleaseSnapshot> findWindow(java.time.Instant from, java.time.Instant to, int limit) {
        return jdbc.query(
                        "SELECT * FROM release_snapshot "
                                + "WHERE released_at >= ? AND released_at < ? ORDER BY released_at, id LIMIT ?",
                        (rs, n) -> mapSnapshot(rs),
                        JdbcTimes.toDb(from), JdbcTimes.toDb(to), limit).stream()
                .map(this::withComponents)
                .toList();
    }

    private ReleaseSnapshot withComponents(ReleaseSnapshot snapshot) {
        List<UncertaintyComponent> components = jdbc.query(
                "SELECT * FROM release_snapshot_component WHERE snapshot_id = ? ORDER BY id",
                (rs, n) -> new UncertaintyComponent(
                        rs.getLong("id"), snapshot.measurementId(), snapshot.budgetVersion(),
                        rs.getString("name"), rs.getBigDecimal("magnitude"), snapshot.releasedAt()),
                snapshot.id());
        return new ReleaseSnapshot(snapshot.id(), snapshot.batchId(), snapshot.measurementId(),
                snapshot.measurementKey(), snapshot.measurementVersion(), snapshot.computedValue(),
                snapshot.passedBias(), snapshot.budgetVersion(), snapshot.combinedUncertainty(),
                snapshot.formula(), snapshot.thresholdId(), snapshot.thresholdVersion(),
                snapshot.thresholdValue(), snapshot.uncertaintyOk(), snapshot.decision(),
                snapshot.releasedBy(), snapshot.releasedAt(), components);
    }

    private static ReleaseSnapshot mapSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReleaseSnapshot(
                rs.getLong("id"),
                rs.getString("batch_id"),
                rs.getLong("measurement_id"),
                rs.getString("measurement_key"),
                rs.getInt("measurement_version"),
                rs.getBigDecimal("computed_value"),
                rs.getBoolean("passed_bias"),
                rs.getInt("budget_version"),
                rs.getBigDecimal("combined_uncertainty"),
                rs.getString("formula"),
                rs.getLong("threshold_id"),
                rs.getInt("threshold_version"),
                rs.getBigDecimal("threshold_value"),
                rs.getBoolean("uncertainty_ok"),
                rs.getString("decision"),
                rs.getString("released_by"),
                JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
                List.of());
    }
}
