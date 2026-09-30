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
import com.example.starter.maintenance.domain.WitnessRevision;

/**
 * 设备工时保养数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class EquipmentRepository {

    private final JdbcTemplate jdbc;

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static final RowMapper<Equipment> EQUIPMENT_MAPPER = (rs, rowNum) -> new Equipment(
            rs.getString("equipment_id"),
            rs.getLong("maintenance_period_minutes"),
            rs.getLong("witness_threshold_minutes"),
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

    private static final RowMapper<WitnessRevision> WITNESS_MAPPER = (rs, rowNum) -> new WitnessRevision(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("pending_revision_no"),
            rs.getLong("cumulative_minutes"),
            rs.getLong("threshold_minutes"),
            rs.getLong("base_cumulative_minutes"),
            rs.getLong("difference_minutes"),
            rs.getString("request_id"),
            rs.getString("submitted_by"),
            readInstant(rs, "submitted_at"));

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes,
                                long witnessThresholdMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes,"
                        + " witness_threshold_minutes, version, created_at) VALUES (?, ?, ?, 1, ?)",
                equipmentId, maintenancePeriodMinutes, witnessThresholdMinutes, utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, witness_threshold_minutes, version"
                        + " FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, witness_threshold_minutes, version"
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

    /** 采样时刻严格早于给定时刻的最近一条有效读数。 */
    public Optional<Reading> findPrevReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条有效读数。 */
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

    // ---------- 读数修订历史 ----------

    /**
     * 追加一条修订历史。阈值内即时生效修订（revisionNo &gt; 1）与初始登记（revisionNo=1）
     * 的见证元数据传 null；见证生效修订传入阈值、差额、提交人与见证人。
     */
    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String requestId,
                               Long thresholdMinutes, Long differenceMinutes,
                               String submittedBy, String witnessedBy, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, request_id, threshold_minutes, difference_minutes,"
                        + " submitted_by, witnessed_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeMinutes, requestId,
                thresholdMinutes, differenceMinutes, submittedBy, witnessedBy, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, request_id, threshold_minutes,"
                        + " difference_minutes, submitted_by, witnessed_by, created_at"
                        + " FROM reading_revision WHERE equipment_id = ? AND reading_id = ?"
                        + " ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        (Long) rs.getObject("threshold_minutes"),
                        (Long) rs.getObject("difference_minutes"),
                        rs.getString("submitted_by"),
                        rs.getString("witnessed_by"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, String requestId,
                              Long thresholdMinutes, Long differenceMinutes,
                              String submittedBy, String witnessedBy, Instant createdAt) {
    }

    /** 按产生该修订的 requestId 查询修订历史行（见证生效的大幅修订行 requestId 为原修订申请的 requestId）。 */
    public Optional<RevisionRow> findRevisionByRequestId(String equipmentId, String readingId,
                                                         String requestId) {
        List<RevisionRow> rows = jdbc.query(
                "SELECT revision_no, cumulative_minutes, request_id, threshold_minutes,"
                        + " difference_minutes, submitted_by, witnessed_by, created_at"
                        + " FROM reading_revision WHERE equipment_id = ? AND reading_id = ?"
                        + " AND request_id = ? ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        (Long) rs.getObject("threshold_minutes"),
                        (Long) rs.getObject("difference_minutes"),
                        rs.getString("submitted_by"),
                        rs.getString("witnessed_by"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId, requestId);
        return rows.stream().findFirst();
    }

    // ---------- 大幅修订见证 ----------

    public void insertWitness(WitnessRevision witness, Instant now) {
        jdbc.update("INSERT INTO reading_witness (equipment_id, reading_id, pending_revision_no,"
                        + " cumulative_minutes, threshold_minutes, base_cumulative_minutes,"
                        + " difference_minutes, request_id, submitted_by, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                witness.equipmentId(), witness.readingId(), witness.pendingRevisionNo(),
                witness.cumulativeMinutes(), witness.thresholdMinutes(),
                witness.baseCumulativeMinutes(), witness.differenceMinutes(),
                witness.requestId(), witness.submittedBy(), utc(now));
    }

    /** 该读数当前是否存在待确认修订。 */
    public Optional<WitnessRevision> findPendingWitness(String equipmentId, String readingId) {
        List<WitnessRevision> rows = jdbc.query(
                "SELECT equipment_id, reading_id, pending_revision_no, cumulative_minutes,"
                        + " threshold_minutes, base_cumulative_minutes, difference_minutes, request_id,"
                        + " submitted_by, submitted_at"
                        + " FROM reading_witness WHERE equipment_id = ? AND reading_id = ?",
                WITNESS_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<WitnessRevision> findWitnessByRequestId(String requestId) {
        List<WitnessRevision> rows = jdbc.query(
                "SELECT equipment_id, reading_id, pending_revision_no, cumulative_minutes,"
                        + " threshold_minutes, base_cumulative_minutes, difference_minutes, request_id,"
                        + " submitted_by, submitted_at"
                        + " FROM reading_witness WHERE request_id = ?",
                WITNESS_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /** 见证生效后删除待确认行（历史留痕由 reading_revision 承载）。 */
    public void deleteWitness(String equipmentId, String readingId) {
        jdbc.update("DELETE FROM reading_witness WHERE equipment_id = ? AND reading_id = ?",
                equipmentId, readingId);
    }

    /** 设备下全部待确认修订（按提交时刻倒序）。 */
    public List<WitnessRevision> listWitnesses(String equipmentId) {
        return jdbc.query(
                "SELECT equipment_id, reading_id, pending_revision_no, cumulative_minutes,"
                        + " threshold_minutes, base_cumulative_minutes, difference_minutes, request_id,"
                        + " submitted_by, submitted_at"
                        + " FROM reading_witness WHERE equipment_id = ?"
                        + " ORDER BY submitted_at DESC, reading_id ASC",
                WITNESS_MAPPER, equipmentId);
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

    /** 该有效读数是否已被任意历史保养记录锚定（锚定后不可修订）。 */
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
