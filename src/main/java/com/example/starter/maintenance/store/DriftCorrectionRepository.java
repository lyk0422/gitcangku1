package com.example.starter.maintenance.store;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 漂移修正与保养快照数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 * 修正单、锚点、明细与快照均为激活事务内一次性写入的证据数据，只增不改。
 */
@Repository
public class DriftCorrectionRepository {

    private final JdbcTemplate jdbc;

    public DriftCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---------- 修正单单头 ----------

    public void insertCorrection(CorrectionRow row) {
        jdbc.update("INSERT INTO drift_correction (correction_key, equipment_id, request_id,"
                        + " anchor_count, first_sampled_at, last_sampled_at, affected_count,"
                        + " maintenance_snapshot_version, equipment_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.correctionKey(), row.equipmentId(), row.requestId(), row.anchorCount(),
                utc(row.firstSampledAt()), utc(row.lastSampledAt()), row.affectedCount(),
                row.maintenanceSnapshotVersion(), row.equipmentVersion(), utc(row.createdAt()));
    }

    public boolean existsCorrection(String correctionKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM drift_correction WHERE correction_key = ?",
                Integer.class, correctionKey);
        return count != null && count > 0;
    }

    public Optional<CorrectionRow> findCorrection(String equipmentId, String correctionKey) {
        List<CorrectionRow> rows = jdbc.query(
                "SELECT correction_key, equipment_id, request_id, anchor_count,"
                        + " first_sampled_at, last_sampled_at, affected_count,"
                        + " maintenance_snapshot_version, equipment_version, created_at"
                        + " FROM drift_correction WHERE equipment_id = ? AND correction_key = ?",
                (rs, rowNum) -> mapCorrection(rs), equipmentId, correctionKey);
        return rows.stream().findFirst();
    }

    /** 设备修正单列表：按激活时刻、correction_key 稳定升序。 */
    public List<CorrectionRow> listCorrections(String equipmentId) {
        return jdbc.query(
                "SELECT correction_key, equipment_id, request_id, anchor_count,"
                        + " first_sampled_at, last_sampled_at, affected_count,"
                        + " maintenance_snapshot_version, equipment_version, created_at"
                        + " FROM drift_correction WHERE equipment_id = ?"
                        + " ORDER BY created_at ASC, correction_key ASC",
                (rs, rowNum) -> mapCorrection(rs), equipmentId);
    }

    private static CorrectionRow mapCorrection(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CorrectionRow(
                rs.getString("correction_key"),
                rs.getString("equipment_id"),
                rs.getString("request_id"),
                rs.getInt("anchor_count"),
                readInstant(rs, "first_sampled_at"),
                readInstant(rs, "last_sampled_at"),
                rs.getInt("affected_count"),
                rs.getLong("maintenance_snapshot_version"),
                rs.getLong("equipment_version"),
                readInstant(rs, "created_at"));
    }

    public record CorrectionRow(String correctionKey, String equipmentId, String requestId,
                                int anchorCount, Instant firstSampledAt, Instant lastSampledAt,
                                int affectedCount, long maintenanceSnapshotVersion,
                                long equipmentVersion, Instant createdAt) {
    }

    // ---------- 修正锚点 ----------

    public void insertAnchor(String correctionKey, AnchorRow row) {
        jdbc.update("INSERT INTO drift_correction_anchor (correction_key, seq, reading_id,"
                        + " sampled_at, expected_revision_no, calibrated_millis)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                correctionKey, row.seq(), row.readingId(), utc(row.sampledAt()),
                row.expectedRevisionNo(), row.calibratedMillis());
    }

    /** 锚点按 seq（采样时刻升序）稳定排序。 */
    public List<AnchorRow> listAnchors(String correctionKey) {
        return jdbc.query(
                "SELECT seq, reading_id, sampled_at, expected_revision_no, calibrated_millis"
                        + " FROM drift_correction_anchor WHERE correction_key = ? ORDER BY seq ASC",
                (rs, rowNum) -> new AnchorRow(
                        rs.getInt("seq"),
                        rs.getString("reading_id"),
                        readInstant(rs, "sampled_at"),
                        rs.getInt("expected_revision_no"),
                        rs.getLong("calibrated_millis")),
                correctionKey);
    }

    public record AnchorRow(int seq, String readingId, Instant sampledAt,
                            int expectedRevisionNo, long calibratedMillis) {
    }

    // ---------- 修正影响明细 ----------

    public void insertItem(String correctionKey, ItemRow row) {
        jdbc.update("INSERT INTO drift_correction_item (correction_key, seq, reading_id,"
                        + " sampled_at, segment_index, old_millis, new_millis,"
                        + " old_revision_no, new_revision_no)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                correctionKey, row.seq(), row.readingId(), utc(row.sampledAt()),
                row.segmentIndex(), row.oldMillis(), row.newMillis(),
                row.oldRevisionNo(), row.newRevisionNo());
    }

    /** 明细按 seq（采样时刻升序）稳定排序。 */
    public List<ItemRow> listItems(String correctionKey) {
        return jdbc.query(
                "SELECT seq, reading_id, sampled_at, segment_index, old_millis, new_millis,"
                        + " old_revision_no, new_revision_no"
                        + " FROM drift_correction_item WHERE correction_key = ? ORDER BY seq ASC",
                (rs, rowNum) -> new ItemRow(
                        rs.getInt("seq"),
                        rs.getString("reading_id"),
                        readInstant(rs, "sampled_at"),
                        rs.getInt("segment_index"),
                        rs.getLong("old_millis"),
                        rs.getLong("new_millis"),
                        rs.getInt("old_revision_no"),
                        rs.getInt("new_revision_no")),
                correctionKey);
    }

    public record ItemRow(int seq, String readingId, Instant sampledAt, int segmentIndex,
                          long oldMillis, long newMillis, int oldRevisionNo, int newRevisionNo) {
    }

    // ---------- 保养快照 ----------

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO maintenance_snapshot (equipment_id, snapshot_version, correction_key,"
                        + " latest_reading_id, latest_sampled_at, latest_cumulative_millis,"
                        + " anchor_cumulative_millis, run_millis, period_minutes, status,"
                        + " next_threshold_millis, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.equipmentId(), row.snapshotVersion(), row.correctionKey(),
                row.latestReadingId(), utc(row.latestSampledAt()), row.latestCumulativeMillis(),
                row.anchorCumulativeMillis(), row.runMillis(), row.periodMinutes(), row.status(),
                row.nextThresholdMillis(), utc(row.createdAt()));
    }

    /** 当前最大快照版本号；无快照时返回 0（调用方须持有设备行锁以保证递增唯一）。 */
    public long maxSnapshotVersion(String equipmentId) {
        Long max = jdbc.queryForObject(
                "SELECT MAX(snapshot_version) FROM maintenance_snapshot WHERE equipment_id = ?",
                Long.class, equipmentId);
        return max == null ? 0L : max;
    }

    /** 快照历史：按版本号稳定升序。 */
    public List<SnapshotRow> listSnapshots(String equipmentId) {
        return jdbc.query(
                "SELECT equipment_id, snapshot_version, correction_key, latest_reading_id,"
                        + " latest_sampled_at, latest_cumulative_millis, anchor_cumulative_millis,"
                        + " run_millis, period_minutes, status, next_threshold_millis, created_at"
                        + " FROM maintenance_snapshot WHERE equipment_id = ?"
                        + " ORDER BY snapshot_version ASC",
                (rs, rowNum) -> new SnapshotRow(
                        rs.getString("equipment_id"),
                        rs.getLong("snapshot_version"),
                        rs.getString("correction_key"),
                        rs.getString("latest_reading_id"),
                        readInstant(rs, "latest_sampled_at"),
                        rs.getLong("latest_cumulative_millis"),
                        rs.getLong("anchor_cumulative_millis"),
                        rs.getLong("run_millis"),
                        rs.getLong("period_minutes"),
                        rs.getString("status"),
                        rs.getLong("next_threshold_millis"),
                        readInstant(rs, "created_at")),
                equipmentId);
    }

    public record SnapshotRow(String equipmentId, long snapshotVersion, String correctionKey,
                              String latestReadingId, Instant latestSampledAt,
                              long latestCumulativeMillis, long anchorCumulativeMillis,
                              long runMillis, long periodMinutes, String status,
                              long nextThresholdMillis, Instant createdAt) {
    }
}
