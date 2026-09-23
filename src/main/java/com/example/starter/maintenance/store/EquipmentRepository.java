package com.example.starter.maintenance.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;

/**
 * 设备工时保养数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class EquipmentRepository {

    private final JdbcTemplate jdbc;

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static final RowMapper<Equipment> EQUIPMENT_MAPPER = (rs, rowNum) -> new Equipment(
            rs.getString("equipment_id"),
            rs.getLong("maintenance_period_minutes"),
            rs.getLong("version"),
            rs.getLong("maintenance_snapshot_version"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> {
        BigDecimal hours = rs.getBigDecimal("cumulative_hours");
        long minutes = rs.getLong("cumulative_minutes");
        return new Reading(
                rs.getString("equipment_id"),
                rs.getString("reading_id"),
                readInstant(rs, "sampled_at"),
                minutes,
                rs.getInt("revision_no"),
                hours != null ? hours : BigDecimal.valueOf(minutes).divide(Reading.HOURS_PER_SIXTY, 6,
                        RoundingMode.HALF_UP));
    };

    private static final String EQUIPMENT_COLUMNS =
            "equipment_id, maintenance_period_minutes, version, maintenance_snapshot_version";

    private static final String READING_COLUMNS =
            "equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no, cumulative_hours";

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) -> new MaintenanceRecord(
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("anchor_revision_no"),
            readInstant(rs, "anchor_sampled_at"),
            rs.getLong("anchor_cumulative_minutes"),
            readInstant(rs, "completed_at"));

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes, version, created_at)"
                        + " VALUES (?, ?, 1, ?)",
                equipmentId, maintenancePeriodMinutes, utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT " + EQUIPMENT_COLUMNS + " FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT " + EQUIPMENT_COLUMNS + " FROM equipment"
                        + " WHERE equipment_id = ? FOR UPDATE",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
    }

    /** 漂移修正成功：设备版本与保养快照版本在同一语句内同时加一，保证原子。 */
    public void incrementVersionAndSnapshot(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1,"
                + " maintenance_snapshot_version = maintenance_snapshot_version + 1"
                + " WHERE equipment_id = ?", equipmentId);
    }

    // ---------- 读数 ----------

    public void insertReading(Reading reading, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, sampled_at, cumulative_minutes,"
                        + " revision_no, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
                reading.equipmentId(), reading.readingId(), utc(reading.sampledAt()),
                reading.cumulativeMinutes(), utc(createdAt), utc(createdAt));
    }

    public Optional<Reading> findReading(String equipmentId, String readingId) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    public Optional<Reading> findLatestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void updateReadingValue(String equipmentId, String readingId, long cumulativeMinutes,
                                   int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_minutes = ?, revision_no = ?, updated_at = ?,"
                        + " cumulative_hours = ? WHERE equipment_id = ? AND reading_id = ?",
                cumulativeMinutes, newRevisionNo, utc(updatedAt),
                BigDecimal.valueOf(cumulativeMinutes).divide(Reading.HOURS_PER_SIXTY, 6,
                        RoundingMode.HALF_UP),
                equipmentId, readingId);
    }

    /** 漂移修正激活：写入精确工时（0.001 小时）、兼容分钟值（四舍五入）与新版本号。 */
    public void updateReadingCorrected(String equipmentId, String readingId,
                                       BigDecimal cumulativeHours, long cumulativeMinutes,
                                       int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_hours = ?, cumulative_minutes = ?,"
                        + " revision_no = ?, updated_at = ? WHERE equipment_id = ? AND reading_id = ?",
                cumulativeHours, cumulativeMinutes, newRevisionNo, utc(updatedAt),
                equipmentId, readingId);
    }

    /**
     * 重读闭区间内完整读数（含边界），按采样时刻、读数标识稳定排序。
     * 激活事务内调用，确保基于最新已提交数据重算。
     */
    public List<Reading> listReadingsBetween(String equipmentId, Instant start, Instant end) {
        return jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND sampled_at >= ? AND sampled_at <= ?"
                        + " ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId, utc(start), utc(end));
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String requestId, Instant createdAt) {
        insertRevision(equipmentId, readingId, revisionNo, cumulativeMinutes, "REPORT",
                requestId, createdAt);
    }

    /** 普通读数修订版本：分钟值工时，change_type=REVISE。 */
    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String changeType,
                               String requestId, Instant createdAt) {
        insertRevision(equipmentId, readingId, revisionNo,
                BigDecimal.valueOf(cumulativeMinutes).divide(Reading.HOURS_PER_SIXTY, 6,
                        RoundingMode.HALF_UP),
                changeType, requestId, createdAt);
    }

    /** 追加读数新版本（旧版本不可变）；漂移修正版本 change_type=DRIFT_CORRECTION 且工时为 3 位小数。 */
    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               BigDecimal cumulativeHours, String changeType,
                               String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, cumulative_hours, change_type, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo,
                cumulativeHours.multiply(Reading.HOURS_PER_SIXTY).setScale(0, RoundingMode.HALF_UP)
                        .longValue(),
                cumulativeHours, changeType, requestId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, cumulative_hours, change_type,"
                        + " request_id, created_at FROM reading_revision"
                        + " WHERE equipment_id = ? AND reading_id = ? ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getBigDecimal("cumulative_hours"),
                        rs.getString("change_type"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, BigDecimal cumulativeHours,
                              String changeType, String requestId, Instant createdAt) {
    }

    // ---------- 保养记录 ----------

    public long insertMaintenance(String equipmentId, String readingId, int anchorRevisionNo,
                                  Instant anchorSampledAt, long anchorCumulativeMinutes,
                                  String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_cumulative_minutes, request_id, completed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setInt(3, anchorRevisionNo);
            ps.setObject(4, utc(anchorSampledAt));
            ps.setLong(5, anchorCumulativeMinutes);
            ps.setString(6, requestId);
            ps.setObject(7, utc(completedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("保养记录主键生成失败");
        }
        return key.longValue();
    }

    /** 最近一次保养（锚点时间最大者；锚点时间严格递增，故唯一）。 */
    public Optional<MaintenanceRecord> findLastMaintenance(String equipmentId) {
        List<MaintenanceRecord> rows = jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at ASC, maintenance_id ASC",
                MAINTENANCE_MAPPER, equipmentId);
    }

    /** 该读数是否已被任意历史保养记录锚定（锚定后不可修订）。 */
    public boolean existsMaintenanceAnchoringReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ? AND reading_id = ?",
                Integer.class, equipmentId, readingId);
        return count != null && count > 0;
    }

    // ---------- 幂等去重 ----------

    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, operation, request_fingerprint, response_body"
                        + " FROM idempotency_request WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("operation"),
                        rs.getString("request_fingerprint"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(String requestId, String operation, String fingerprint,
                                  String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_request (request_id, operation, request_fingerprint,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, responseBody, utc(createdAt));
    }

    public record IdempotencyRow(String requestId, String operation, String fingerprint, String responseBody) {
    }

    // ---------- 时钟漂移修正 ----------

    public record DriftCorrectionRow(
            String correctionId, String equipmentId, long expectedVersion,
            long equipmentVersionAfter, long maintenanceSnapshotVersion,
            Instant intervalStart, Instant intervalEnd, String requestId,
            String status, Instant activatedAt) {
    }

    public record DriftAnchorRow(
            String correctionId, int positionNo, String readingId, Instant sampledAt,
            int expectedRevisionNo, BigDecimal calibratedHours) {
    }

    public record DriftReadingRow(
            String correctionId, String readingId, Instant sampledAt, boolean anchor,
            Integer segmentIndex, boolean frozen, int oldRevisionNo, int newRevisionNo,
            BigDecimal oldHours, BigDecimal newHours) {
    }

    public void insertDriftCorrection(DriftCorrectionRow row) {
        jdbc.update("INSERT INTO drift_correction (correction_id, equipment_id, expected_version,"
                        + " equipment_version_after, maintenance_snapshot_version, interval_start,"
                        + " interval_end, request_id, status, activated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.correctionId(), row.equipmentId(), row.expectedVersion(),
                row.equipmentVersionAfter(), row.maintenanceSnapshotVersion(),
                utc(row.intervalStart()), utc(row.intervalEnd()), row.requestId(),
                row.status(), utc(row.activatedAt()));
    }

    public Optional<DriftCorrectionRow> findDriftCorrection(String correctionId) {
        List<DriftCorrectionRow> rows = jdbc.query(
                "SELECT correction_id, equipment_id, expected_version, equipment_version_after,"
                        + " maintenance_snapshot_version, interval_start, interval_end, request_id,"
                        + " status, activated_at FROM drift_correction WHERE correction_id = ?",
                (rs, n) -> new DriftCorrectionRow(
                        rs.getString("correction_id"), rs.getString("equipment_id"),
                        rs.getLong("expected_version"), rs.getLong("equipment_version_after"),
                        rs.getLong("maintenance_snapshot_version"),
                        readInstant(rs, "interval_start"), readInstant(rs, "interval_end"),
                        rs.getString("request_id"), rs.getString("status"),
                        readInstant(rs, "activated_at")),
                correctionId);
        return rows.stream().findFirst();
    }

    public void insertDriftAnchor(DriftAnchorRow row) {
        jdbc.update("INSERT INTO drift_correction_anchor (correction_id, position_no, reading_id,"
                        + " sampled_at, expected_revision_no, calibrated_hours)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.correctionId(), row.positionNo(), row.readingId(), utc(row.sampledAt()),
                row.expectedRevisionNo(), row.calibratedHours());
    }

    public List<DriftAnchorRow> listDriftAnchors(String correctionId) {
        return jdbc.query(
                "SELECT correction_id, position_no, reading_id, sampled_at, expected_revision_no,"
                        + " calibrated_hours FROM drift_correction_anchor WHERE correction_id = ?"
                        + " ORDER BY position_no ASC",
                (rs, n) -> new DriftAnchorRow(
                        rs.getString("correction_id"), rs.getInt("position_no"),
                        rs.getString("reading_id"), readInstant(rs, "sampled_at"),
                        rs.getInt("expected_revision_no"), rs.getBigDecimal("calibrated_hours")),
                correctionId);
    }

    public void insertDriftReading(DriftReadingRow row) {
        jdbc.update("INSERT INTO drift_correction_reading (correction_id, reading_id, sampled_at,"
                        + " is_anchor, segment_index, frozen, old_revision_no, new_revision_no,"
                        + " old_hours, new_hours) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.correctionId(), row.readingId(), utc(row.sampledAt()), row.anchor(),
                row.segmentIndex(), row.frozen(), row.oldRevisionNo(), row.newRevisionNo(),
                row.oldHours(), row.newHours());
    }

    public List<DriftReadingRow> listDriftReadings(String correctionId) {
        return jdbc.query(
                "SELECT correction_id, reading_id, sampled_at, is_anchor, segment_index, frozen,"
                        + " old_revision_no, new_revision_no, old_hours, new_hours"
                        + " FROM drift_correction_reading WHERE correction_id = ?"
                        + " ORDER BY sampled_at ASC, reading_id ASC",
                (rs, n) -> {
                    int segment = rs.getInt("segment_index");
                    return new DriftReadingRow(
                            rs.getString("correction_id"), rs.getString("reading_id"),
                            readInstant(rs, "sampled_at"), rs.getBoolean("is_anchor"),
                            rs.wasNull() ? null : segment, rs.getBoolean("frozen"),
                            rs.getInt("old_revision_no"), rs.getInt("new_revision_no"),
                            rs.getBigDecimal("old_hours"), rs.getBigDecimal("new_hours"));
                },
                correctionId);
    }

    /** 保养快照：一次漂移修正只插入一行。 */
    public long insertMaintenanceSnapshot(String equipmentId, long snapshotVersion,
                                          String correctionId, String latestReadingId,
                                          Instant latestSampledAt, BigDecimal latestHours,
                                          Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance_snapshot (equipment_id, snapshot_version, correction_id,"
                            + " latest_reading_id, latest_sampled_at, latest_cumulative_hours, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setLong(2, snapshotVersion);
            ps.setString(3, correctionId);
            ps.setString(4, latestReadingId);
            ps.setObject(5, latestSampledAt == null ? null : utc(latestSampledAt));
            ps.setBigDecimal(6, latestHours);
            ps.setObject(7, utc(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("保养快照主键生成失败");
        }
        return key.longValue();
    }

    public void insertMaintenanceItemSnapshot(long snapshotId, String itemKey, String status,
                                              BigDecimal runHours, BigDecimal nextThresholdHours,
                                              String lastAnchorReadingId,
                                              BigDecimal lastAnchorCumulativeHours) {
        jdbc.update("INSERT INTO maintenance_item_snapshot (snapshot_id, item_key, status, run_hours,"
                        + " next_threshold_hours, last_anchor_reading_id,"
                        + " last_anchor_cumulative_hours) VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshotId, itemKey, status, runHours, nextThresholdHours,
                lastAnchorReadingId, lastAnchorCumulativeHours);
    }

    public record MaintenanceItemSnapshotRow(
            String itemKey, String status, BigDecimal runHours, BigDecimal nextThresholdHours,
            String lastAnchorReadingId, BigDecimal lastAnchorCumulativeHours) {
    }

    /** 读取某次漂移修正产生的保养快照项目（按项目键稳定排序）。 */
    public List<MaintenanceItemSnapshotRow> listMaintenanceItemsOfCorrection(String correctionId) {
        return jdbc.query(
                "SELECT i.item_key, i.status, i.run_hours, i.next_threshold_hours,"
                        + " i.last_anchor_reading_id, i.last_anchor_cumulative_hours"
                        + " FROM maintenance_item_snapshot i"
                        + " JOIN maintenance_snapshot s ON i.snapshot_id = s.snapshot_id"
                        + " WHERE s.correction_id = ? ORDER BY i.item_key ASC",
                (rs, n) -> new MaintenanceItemSnapshotRow(
                        rs.getString("item_key"), rs.getString("status"),
                        rs.getBigDecimal("run_hours"), rs.getBigDecimal("next_threshold_hours"),
                        rs.getString("last_anchor_reading_id"),
                        rs.getBigDecimal("last_anchor_cumulative_hours")),
                correctionId);
    }
}
