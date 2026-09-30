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

import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.RevisionRequest;

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
            rs.getLong("revision_threshold_minutes"),
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

    private static final RowMapper<RevisionRequest> REVISION_REQUEST_MAPPER = (rs, rowNum) -> new RevisionRequest(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getString("request_id"),
            rs.getInt("base_revision_no"),
            rs.getInt("proposed_revision_no"),
            rs.getLong("current_cumulative_minutes"),
            rs.getLong("proposed_cumulative_minutes"),
            rs.getLong("delta_minutes"),
            rs.getLong("threshold_minutes"),
            rs.getString("submitter_id"),
            rs.getString("witness_id"),
            rs.getString("status"),
            rs.getLong("expected_version"),
            readInstant(rs, "created_at"),
            rs.getObject("witnessed_at", OffsetDateTime.class) == null
                    ? null : rs.getObject("witnessed_at", OffsetDateTime.class).toInstant());

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes,
                                long revisionThresholdMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes,"
                                + " revision_threshold_minutes, version, created_at)"
                                + " VALUES (?, ?, ?, 1, ?)",
                equipmentId, maintenancePeriodMinutes, revisionThresholdMinutes, utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, revision_threshold_minutes, version"
                        + " FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, revision_threshold_minutes, version"
                        + " FROM equipment WHERE equipment_id = ? FOR UPDATE",
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

    /**
     * 追加一条修订历史。
     *
     * @param submitterId 修订提交人；初始登记行（revisionNo=1）传 null
     * @param witnessId   见证人；阈值内直接生效或初始登记行传 null
     */
    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String requestId,
                               String submitterId, String witnessId, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, request_id, submitter_id, witness_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeMinutes, requestId,
                submitterId, witnessId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, request_id, submitter_id, witness_id, created_at"
                        + " FROM reading_revision WHERE equipment_id = ? AND reading_id = ?"
                        + " ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        rs.getString("submitter_id"),
                        rs.getString("witness_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, String requestId,
                              String submitterId, String witnessId, Instant createdAt) {
    }

    // ---------- 大幅修订见证申请 ----------

    public void insertRevisionRequest(RevisionRequest request) {
        jdbc.update("INSERT INTO reading_revision_request (equipment_id, reading_id, request_id,"
                        + " base_revision_no, proposed_revision_no, current_cumulative_minutes,"
                        + " proposed_cumulative_minutes, delta_minutes, threshold_minutes,"
                        + " submitter_id, witness_id, status, expected_version, created_at, witnessed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                request.equipmentId(), request.readingId(), request.requestId(),
                request.baseRevisionNo(), request.proposedRevisionNo(),
                request.currentCumulativeMinutes(), request.proposedCumulativeMinutes(),
                request.deltaMinutes(), request.thresholdMinutes(),
                request.submitterId(), request.witnessId(), request.status(),
                request.expectedVersion(), utc(request.createdAt()),
                request.witnessedAt() == null ? null : utc(request.witnessedAt()));
    }

    public Optional<RevisionRequest> findRevisionRequest(String equipmentId, String readingId,
                                                         String requestId) {
        List<RevisionRequest> rows = jdbc.query(
                revisionRequestSelect() + " WHERE equipment_id = ? AND reading_id = ? AND request_id = ?",
                REVISION_REQUEST_MAPPER, equipmentId, readingId, requestId);
        return rows.stream().findFirst();
    }

    /** 该读数当前待确认的大幅修订申请；至多一条（新申请提交时旧申请被置为 SUPERSEDED）。 */
    public Optional<RevisionRequest> findPendingRevisionRequest(String equipmentId, String readingId) {
        List<RevisionRequest> rows = jdbc.query(
                revisionRequestSelect() + " WHERE equipment_id = ? AND reading_id = ? AND status = '"
                        + RevisionRequest.STATUS_PENDING + "'",
                REVISION_REQUEST_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    /** 设备下全部待确认申请，按读数标识索引，用于读数列表一并展示。 */
    public List<RevisionRequest> listPendingRevisionRequests(String equipmentId) {
        return jdbc.query(
                revisionRequestSelect() + " WHERE equipment_id = ? AND status = '"
                        + RevisionRequest.STATUS_PENDING + "' ORDER BY reading_id ASC, created_at ASC",
                REVISION_REQUEST_MAPPER, equipmentId);
    }

    public List<RevisionRequest> listRevisionRequests(String equipmentId, String readingId) {
        return jdbc.query(
                revisionRequestSelect() + " WHERE equipment_id = ? AND reading_id = ?"
                        + " ORDER BY created_at ASC",
                REVISION_REQUEST_MAPPER, equipmentId, readingId);
    }

    /** 将该读数的待确认申请置为已被取代（读数在见证前产生了新的修订或被保养锚定）。 */
    public int markPendingRevisionSuperseded(String equipmentId, String readingId) {
        return jdbc.update("UPDATE reading_revision_request SET status = '"
                        + RevisionRequest.STATUS_SUPERSEDED
                        + "' WHERE equipment_id = ? AND reading_id = ? AND status = '"
                        + RevisionRequest.STATUS_PENDING + "'",
                equipmentId, readingId);
    }

    /**
     * 见证生效：仅当申请仍为 PENDING 时条件更新，返回受影响行数（0 表示已被取代/已生效）。
     */
    public int markRevisionRequestEffective(String equipmentId, String readingId, String requestId,
                                            String witnessId, Instant witnessedAt) {
        return jdbc.update("UPDATE reading_revision_request SET status = '"
                        + RevisionRequest.STATUS_EFFECTIVE
                        + "', witness_id = ?, witnessed_at = ?"
                        + " WHERE equipment_id = ? AND reading_id = ? AND request_id = ? AND status = '"
                        + RevisionRequest.STATUS_PENDING + "'",
                witnessId, utc(witnessedAt), equipmentId, readingId, requestId);
    }

    private static String revisionRequestSelect() {
        return "SELECT equipment_id, reading_id, request_id, base_revision_no, proposed_revision_no,"
                + " current_cumulative_minutes, proposed_cumulative_minutes, delta_minutes,"
                + " threshold_minutes, submitter_id, witness_id, status, expected_version,"
                + " created_at, witnessed_at FROM reading_revision_request";
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
}
