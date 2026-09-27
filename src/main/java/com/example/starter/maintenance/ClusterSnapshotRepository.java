package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 冲突簇合并快照持久化：快照头、证据明细与固化窗口版本同事务写入，历史永不修改。
 */
@Repository
public class ClusterSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClusterSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ClusterSnapshotRecord> HEADER_MAPPER = (rs, rowNum) -> new ClusterSnapshotRecord(
            rs.getLong("snapshot_id"),
            rs.getString("cluster_id"),
            rs.getString("station_id"),
            rs.getInt("sequence_no"),
            SnapshotStatus.valueOf(rs.getString("status")),
            rs.getString("winner_observation_id"),
            rs.getTimestamp("created_at_utc").toInstant(),
            List.of(),
            List.of());

    /**
     * 插入快照头并回填自增主键。
     */
    public long insertHeader(String clusterId, String stationId, int sequence, SnapshotStatus status,
                             String winnerObservationId, java.time.Instant createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cluster_snapshot (cluster_id, station_id, sequence_no, status, "
                            + "winner_observation_id, created_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clusterId);
            ps.setString(2, stationId);
            ps.setInt(3, sequence);
            ps.setString(4, status.name());
            ps.setString(5, winnerObservationId);
            ps.setTimestamp(6, Timestamp.from(createdAtUtc));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to obtain cluster_snapshot generated key");
        }
        return key.longValue();
    }

    /**
     * 插入一条快照证据明细。
     */
    public void insertEvidence(long snapshotId, int ordinal, SnapshotEvidence evidence) {
        jdbcTemplate.update(
                "INSERT INTO cluster_snapshot_evidence (snapshot_id, ordinal, observation_id, submitted_at_utc, "
                        + "payload, maintenance_affected, matched_window_id, matched_maintenance_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotId, ordinal,
                evidence.observationId(),
                Timestamp.from(evidence.submittedAtUtc()),
                evidence.payload(),
                evidence.maintenanceAffected(),
                evidence.matchedWindowId(),
                evidence.matchedMaintenanceVersion());
    }

    /**
     * 插入一条快照固化窗口版本。
     */
    public void insertWindow(long snapshotId, int ordinal, SnapshotWindow window) {
        jdbcTemplate.update(
                "INSERT INTO cluster_snapshot_window (snapshot_id, ordinal, window_id, maintenance_version) "
                        + "VALUES (?, ?, ?, ?)",
                snapshotId, ordinal, window.windowId(), window.maintenanceVersion());
    }

    /**
     * 按主键查询快照头。
     */
    public Optional<ClusterSnapshotRecord> findHeaderById(long snapshotId) {
        return jdbcTemplate.query(
                        "SELECT snapshot_id, cluster_id, station_id, sequence_no, status, "
                                + "winner_observation_id, created_at_utc FROM cluster_snapshot WHERE snapshot_id = ?",
                        HEADER_MAPPER, snapshotId)
                .stream().findFirst();
    }

    /**
     * 按冲突簇查询最新快照头（不加锁）；同簇写操作由站点行锁与唯一键串行化。
     */
    public Optional<ClusterSnapshotRecord> findLatestHeader(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT snapshot_id, cluster_id, station_id, sequence_no, status, "
                                + "winner_observation_id, created_at_utc FROM cluster_snapshot "
                                + "WHERE cluster_id = ? ORDER BY sequence_no DESC FETCH FIRST 1 ROWS ONLY",
                        HEADER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 查询快照全部证据，按固化顺序返回。
     */
    public List<SnapshotEvidence> findEvidence(long snapshotId) {
        return jdbcTemplate.query(
                "SELECT observation_id, submitted_at_utc, payload, maintenance_affected, "
                        + "matched_window_id, matched_maintenance_version FROM cluster_snapshot_evidence "
                        + "WHERE snapshot_id = ? ORDER BY ordinal ASC",
                (rs, rowNum) -> new SnapshotEvidence(
                        rs.getString("observation_id"),
                        rs.getTimestamp("submitted_at_utc").toInstant(),
                        rs.getString("payload"),
                        rs.getBoolean("maintenance_affected"),
                        rs.getString("matched_window_id"),
                        (Integer) rs.getObject("matched_maintenance_version")),
                snapshotId);
    }

    /**
     * 查询快照固化的全部窗口版本，按固化顺序返回。
     */
    public List<SnapshotWindow> findWindows(long snapshotId) {
        return jdbcTemplate.query(
                "SELECT window_id, maintenance_version FROM cluster_snapshot_window "
                        + "WHERE snapshot_id = ? ORDER BY ordinal ASC",
                (rs, rowNum) -> new SnapshotWindow(rs.getString("window_id"), rs.getInt("maintenance_version")),
                snapshotId);
    }

    /**
     * 按冲突簇查询全部快照头（历史诊断用），按序号升序。
     */
    public List<ClusterSnapshotRecord> findHeadersByCluster(String clusterId) {
        return jdbcTemplate.query(
                "SELECT snapshot_id, cluster_id, station_id, sequence_no, status, "
                        + "winner_observation_id, created_at_utc FROM cluster_snapshot "
                        + "WHERE cluster_id = ? ORDER BY sequence_no ASC",
                HEADER_MAPPER, clusterId);
    }
}
