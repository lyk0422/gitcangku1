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

import com.example.starter.maintenance.domain.CertificationSnapshot;
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
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final String EQUIPMENT_COLUMNS =
            "equipment_id, maintenance_period_minutes, version, retired, retired_at";

    private static final String READING_COLUMNS =
            "equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no,"
                    + " status, recorded_by, certified_by, certified_at";

    private static final RowMapper<Equipment> EQUIPMENT_MAPPER = (rs, rowNum) -> new Equipment(
            rs.getString("equipment_id"),
            rs.getLong("maintenance_period_minutes"),
            rs.getLong("version"),
            rs.getBoolean("retired"),
            readInstant(rs, "retired_at"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getInt("revision_no"),
            rs.getString("status"),
            rs.getString("recorded_by"),
            rs.getString("certified_by"),
            readInstant(rs, "certified_at"));

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) -> new MaintenanceRecord(
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("anchor_revision_no"),
            readInstant(rs, "anchor_sampled_at"),
            rs.getLong("anchor_cumulative_minutes"),
            readInstant(rs, "completed_at"));

    private static final RowMapper<CertificationSnapshot> CERTIFICATION_MAPPER = (rs, rowNum) -> new CertificationSnapshot(
            rs.getLong("certification_id"),
            rs.getString("request_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("revision_no"),
            rs.getString("certified_by"),
            rs.getLong("cumulative_minutes"),
            rs.getLong("latest_certified_cumulative_minutes"),
            rs.getLong("run_minutes"),
            rs.getString("maintenance_status"),
            rs.getLong("equipment_version"),
            readInstant(rs, "certified_at"));

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes, version, retired,"
                        + " retired_at, created_at) VALUES (?, ?, 1, FALSE, NULL, ?)",
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

    public void retireEquipment(String equipmentId, Instant retiredAt) {
        jdbc.update("UPDATE equipment SET retired = TRUE, retired_at = ? WHERE equipment_id = ?",
                utc(retiredAt), equipmentId);
    }

    // ---------- 读数 ----------

    public void insertReading(Reading reading, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, sampled_at, cumulative_minutes,"
                        + " revision_no, status, recorded_by, certified_by, certified_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'PENDING', ?, NULL, NULL, ?, ?)",
                reading.equipmentId(), reading.readingId(), utc(reading.sampledAt()),
                reading.cumulativeMinutes(), reading.recordedBy(), utc(createdAt), utc(createdAt));
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

    /** 最新一条已认证读数（参与累计工时与保养阈值判定）。 */
    public Optional<Reading> findLatestCertifiedReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND status = 'CERTIFIED'"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 采样时刻严格早于给定时刻的最近一条已认证读数。 */
    public Optional<Reading> findPrevCertifiedReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND status = 'CERTIFIED' AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条已认证读数。 */
    public Optional<Reading> findNextCertifiedReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? AND status = 'CERTIFIED' AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 修订：更新当前值并回到 PENDING（新修订版本须重新认证）。 */
    public void updateReadingValue(String equipmentId, String readingId, long cumulativeMinutes,
                                   int newRevisionNo, String recordedBy, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_minutes = ?, revision_no = ?, status = 'PENDING',"
                        + " recorded_by = ?, certified_by = NULL, certified_at = NULL, updated_at = ?"
                        + " WHERE equipment_id = ? AND reading_id = ?",
                cumulativeMinutes, newRevisionNo, recordedBy, utc(updatedAt), equipmentId, readingId);
    }

    /** 认证通过：当前修订版本转为 CERTIFIED。 */
    public void certifyReading(String equipmentId, String readingId, String certifiedBy, Instant certifiedAt) {
        jdbc.update("UPDATE reading SET status = 'CERTIFIED', certified_by = ?, certified_at = ?,"
                        + " updated_at = ? WHERE equipment_id = ? AND reading_id = ?",
                certifiedBy, utc(certifiedAt), utc(certifiedAt), equipmentId, readingId);
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                "SELECT " + READING_COLUMNS
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String recordedBy, String requestId,
                               Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, recorded_by, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeMinutes, recordedBy, requestId,
                utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, recorded_by, request_id, created_at"
                        + " FROM reading_revision"
                        + " WHERE equipment_id = ? AND reading_id = ? ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("recorded_by"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, String recordedBy,
                              String requestId, Instant createdAt) {
    }

    // ---------- 认证快照 ----------

    public long insertCertification(String requestId, String equipmentId, String readingId,
                                    int revisionNo, String certifiedBy, long cumulativeMinutes,
                                    long latestCertifiedCumulativeMinutes, long runMinutes,
                                    String maintenanceStatus, long equipmentVersion,
                                    Instant certifiedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO reading_certification (request_id, equipment_id, reading_id,"
                            + " revision_no, certified_by, cumulative_minutes,"
                            + " latest_certified_cumulative_minutes, run_minutes, maintenance_status,"
                            + " equipment_version, certified_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, equipmentId);
            ps.setString(3, readingId);
            ps.setInt(4, revisionNo);
            ps.setString(5, certifiedBy);
            ps.setLong(6, cumulativeMinutes);
            ps.setLong(7, latestCertifiedCumulativeMinutes);
            ps.setLong(8, runMinutes);
            ps.setString(9, maintenanceStatus);
            ps.setLong(10, equipmentVersion);
            ps.setObject(11, utc(certifiedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("认证快照主键生成失败");
        }
        return key.longValue();
    }

    /** 指定读数最近一次认证快照（按认证主键倒序，即最新一次成功认证）。 */
    public Optional<CertificationSnapshot> findLatestCertification(String equipmentId, String readingId) {
        List<CertificationSnapshot> rows = jdbc.query(
                "SELECT certification_id, request_id, equipment_id, reading_id, revision_no,"
                        + " certified_by, cumulative_minutes, latest_certified_cumulative_minutes,"
                        + " run_minutes, maintenance_status, equipment_version, certified_at"
                        + " FROM reading_certification WHERE equipment_id = ? AND reading_id = ?"
                        + " ORDER BY certification_id DESC LIMIT 1",
                CERTIFICATION_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public List<CertificationSnapshot> listCertifications(String equipmentId) {
        return jdbc.query(
                "SELECT certification_id, request_id, equipment_id, reading_id, revision_no,"
                        + " certified_by, cumulative_minutes, latest_certified_cumulative_minutes,"
                        + " run_minutes, maintenance_status, equipment_version, certified_at"
                        + " FROM reading_certification WHERE equipment_id = ?"
                        + " ORDER BY certification_id ASC",
                CERTIFICATION_MAPPER, equipmentId);
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
