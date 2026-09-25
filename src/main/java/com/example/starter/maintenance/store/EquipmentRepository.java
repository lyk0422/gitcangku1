package com.example.starter.maintenance.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
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

import com.example.starter.maintenance.domain.Deferral;
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
            rs.getLong("version"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getInt("revision_no"));

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
                "SELECT equipment_id, maintenance_period_minutes, version FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, version FROM equipment"
                        + " WHERE equipment_id = ? FOR UPDATE",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
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
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    public Optional<Reading> findLatestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void updateReadingValue(String equipmentId, String readingId, long cumulativeMinutes,
                                   int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_minutes = ?, revision_no = ?, updated_at = ?"
                        + " WHERE equipment_id = ? AND reading_id = ?",
                cumulativeMinutes, newRevisionNo, utc(updatedAt), equipmentId, readingId);
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeMinutes, requestId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, request_id, created_at FROM reading_revision"
                        + " WHERE equipment_id = ? AND reading_id = ? ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, String requestId, Instant createdAt) {
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

    // ---------- 保养延期 ----------

    private static final RowMapper<Deferral> DEFERRAL_MAPPER = (rs, rowNum) -> new Deferral(
            rs.getLong("deferral_id"),
            rs.getString("equipment_id"),
            rs.getString("defer_key"),
            rs.getInt("cycle_no"),
            rs.getLong("defer_minutes"),
            rs.getString("reason"),
            rs.getString("applicant"),
            rs.getString("approver"),
            rs.getString("reject_reason"),
            rs.getString("status"),
            rs.getLong("applied_cumulative_minutes"),
            (Long) rs.getObject("original_threshold_minutes"),
            (Long) rs.getObject("new_threshold_minutes"),
            readInstant(rs, "created_at"),
            rs.getObject("decided_at") != null ? readInstant(rs, "decided_at") : null);

    private static final String DEFERRAL_COLUMNS =
            "deferral_id, equipment_id, defer_key, cycle_no, defer_minutes, reason, applicant,"
                    + " approver, reject_reason, status, applied_cumulative_minutes,"
                    + " original_threshold_minutes, new_threshold_minutes, created_at, decided_at";

    public long insertDeferral(String equipmentId, String deferKey, int cycleNo, long deferMinutes,
                               String reason, String applicant, long appliedCumulativeMinutes,
                               String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO deferral (equipment_id, defer_key, cycle_no, defer_minutes, reason,"
                            + " applicant, status, applied_cumulative_minutes, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, deferKey);
            ps.setInt(3, cycleNo);
            ps.setLong(4, deferMinutes);
            ps.setString(5, reason);
            ps.setString(6, applicant);
            ps.setLong(7, appliedCumulativeMinutes);
            ps.setString(8, requestId);
            ps.setObject(9, utc(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("延期记录主键生成失败");
        }
        return key.longValue();
    }

    public Optional<Deferral> findDeferral(String equipmentId, String deferKey) {
        List<Deferral> rows = jdbc.query(
                "SELECT " + DEFERRAL_COLUMNS + " FROM deferral WHERE equipment_id = ? AND defer_key = ?",
                DEFERRAL_MAPPER, equipmentId, deferKey);
        return rows.stream().findFirst();
    }

    /** 当前待审批延期（同一设备同时至多一条，由设备行锁保证）。 */
    public Optional<Deferral> findPendingDeferral(String equipmentId) {
        List<Deferral> rows = jdbc.query(
                "SELECT " + DEFERRAL_COLUMNS + " FROM deferral"
                        + " WHERE equipment_id = ? AND status = 'PENDING'"
                        + " ORDER BY deferral_id DESC LIMIT 1",
                DEFERRAL_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 指定保养周期内已累计批准延期分钟数（无批准记录时为 0）。 */
    public long sumApprovedDeferMinutes(String equipmentId, int cycleNo) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(defer_minutes), 0) FROM deferral"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND status = 'APPROVED'",
                Long.class, equipmentId, cycleNo);
        return sum != null ? sum : 0L;
    }

    public List<Deferral> listDeferrals(String equipmentId) {
        return jdbc.query(
                "SELECT " + DEFERRAL_COLUMNS + " FROM deferral WHERE equipment_id = ?"
                        + " ORDER BY deferral_id ASC",
                DEFERRAL_MAPPER, equipmentId);
    }

    /** 批准：写入审批人、阈值快照与审批时刻；仅当仍为 PENDING 时生效。 */
    public int approveDeferral(long deferralId, String approver, long originalThresholdMinutes,
                               long newThresholdMinutes, String decisionRequestId, Instant decidedAt) {
        return jdbc.update(
                "UPDATE deferral SET status = 'APPROVED', approver = ?,"
                        + " original_threshold_minutes = ?, new_threshold_minutes = ?,"
                        + " decision_request_id = ?, decided_at = ?"
                        + " WHERE deferral_id = ? AND status = 'PENDING'",
                approver, originalThresholdMinutes, newThresholdMinutes,
                decisionRequestId, utc(decidedAt), deferralId);
    }

    /** 拒绝：写入审批人、拒绝理由与审批时刻；仅当仍为 PENDING 时生效。 */
    public int rejectDeferral(long deferralId, String approver, String rejectReason,
                              String decisionRequestId, Instant decidedAt) {
        return jdbc.update(
                "UPDATE deferral SET status = 'REJECTED', approver = ?, reject_reason = ?,"
                        + " decision_request_id = ?, decided_at = ?"
                        + " WHERE deferral_id = ? AND status = 'PENDING'",
                approver, rejectReason, decisionRequestId, utc(decidedAt), deferralId);
    }

    /** 保养完成时将本设备全部待审批延期置为 EXPIRED（周期结束自动失效）。 */
    public void expirePendingDeferrals(String equipmentId, Instant decidedAt) {
        jdbc.update(
                "UPDATE deferral SET status = 'EXPIRED', decided_at = ?"
                        + " WHERE equipment_id = ? AND status = 'PENDING'",
                utc(decidedAt), equipmentId);
    }

    /** 已完成保养次数，即当前保养周期序号。 */
    public int countMaintenances(String equipmentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ?",
                Integer.class, equipmentId);
        return count != null ? count : 0;
    }
}
