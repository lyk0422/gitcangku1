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

import com.example.starter.maintenance.domain.Downtime;
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
            rs.getLong("run_minutes"),
            rs.getLong("deduction_total_minutes"),
            readInstant(rs, "completed_at"));

    private static final RowMapper<Downtime> DOWNTIME_MAPPER = (rs, rowNum) -> new Downtime(
            rs.getLong("downtime_id"),
            rs.getString("downtime_key"),
            rs.getString("equipment_id"),
            readInstant(rs, "start_at"),
            readInstant(rs, "end_at"),
            rs.getString("reason"),
            rs.getLong("deduction_minutes"),
            rs.getString("status"),
            readInstant(rs, "created_at"),
            rs.getObject("revoked_at", OffsetDateTime.class) == null
                    ? null
                    : rs.getObject("revoked_at", OffsetDateTime.class).toInstant());

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

    /** 采样时刻最早的读数（停机区间起止校验用）。 */
    public Optional<Reading> findEarliestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 采样时刻不晚于给定时刻的最近一条读数（停机扣减量取数用）。 */
    public Optional<Reading> findReadingAtOrBefore(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at <= ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
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
                                  long runMinutes, long deductionTotalMinutes,
                                  String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_cumulative_minutes,"
                            + " run_minutes, deduction_total_minutes, request_id, completed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setInt(3, anchorRevisionNo);
            ps.setObject(4, utc(anchorSampledAt));
            ps.setLong(5, anchorCumulativeMinutes);
            ps.setLong(6, runMinutes);
            ps.setLong(7, deductionTotalMinutes);
            ps.setString(8, requestId);
            ps.setObject(9, utc(completedAt));
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
                        + " anchor_sampled_at, anchor_cumulative_minutes,"
                        + " run_minutes, deduction_total_minutes, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes,"
                        + " run_minutes, deduction_total_minutes, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at ASC, maintenance_id ASC",
                MAINTENANCE_MAPPER, equipmentId);
    }

    /** 是否存在严格落在 (startAt, endAt) 内的保养锚点时刻（停机区间不得跨越锚点）。 */
    public boolean existsAnchorBetween(String equipmentId, Instant startAt, Instant endAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ?"
                        + " AND anchor_sampled_at > ? AND anchor_sampled_at < ?",
                Integer.class, equipmentId, utc(startAt), utc(endAt));
        return count != null && count > 0;
    }

    /** 该读数是否已被任意历史保养记录锚定（锚定后不可修订）。 */
    public boolean existsMaintenanceAnchoringReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ? AND reading_id = ?",
                Integer.class, equipmentId, readingId);
        return count != null && count > 0;
    }

    // ---------- 停机区间 ----------

    private static final String DOWNTIME_COLUMNS =
            "downtime_id, downtime_key, equipment_id, start_at, end_at, reason,"
                    + " deduction_minutes, status, created_at, revoked_at";

    public long insertDowntime(String downtimeKey, String equipmentId, Instant startAt, Instant endAt,
                               String reason, long deductionMinutes, String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO downtime (downtime_key, equipment_id, start_at, end_at, reason,"
                            + " deduction_minutes, status, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, '" + Downtime.STATUS_ACTIVE + "', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, downtimeKey);
            ps.setString(2, equipmentId);
            ps.setObject(3, utc(startAt));
            ps.setObject(4, utc(endAt));
            ps.setString(5, reason);
            ps.setLong(6, deductionMinutes);
            ps.setString(7, requestId);
            ps.setObject(8, utc(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("停机记录主键生成失败");
        }
        return key.longValue();
    }

    /** 按全局唯一业务键查询停机记录（含已撤销）。 */
    public Optional<Downtime> findDowntimeByKey(String downtimeKey) {
        List<Downtime> rows = jdbc.query(
                "SELECT " + DOWNTIME_COLUMNS + " FROM downtime WHERE downtime_key = ?",
                DOWNTIME_MAPPER, downtimeKey);
        return rows.stream().findFirst();
    }

    /** 设备内按业务键查询停机记录（含已撤销）。 */
    public Optional<Downtime> findDowntime(String equipmentId, String downtimeKey) {
        List<Downtime> rows = jdbc.query(
                "SELECT " + DOWNTIME_COLUMNS + " FROM downtime"
                        + " WHERE equipment_id = ? AND downtime_key = ?",
                DOWNTIME_MAPPER, equipmentId, downtimeKey);
        return rows.stream().findFirst();
    }

    /** 设备全部停机记录（含已撤销），按开始时刻升序。 */
    public List<Downtime> listDowntimes(String equipmentId) {
        return jdbc.query(
                "SELECT " + DOWNTIME_COLUMNS + " FROM downtime WHERE equipment_id = ?"
                        + " ORDER BY start_at ASC, downtime_id ASC",
                DOWNTIME_MAPPER, equipmentId);
    }

    /** 设备全部生效（ACTIVE）停机记录，按开始时刻升序。 */
    public List<Downtime> listActiveDowntimes(String equipmentId) {
        return jdbc.query(
                "SELECT " + DOWNTIME_COLUMNS + " FROM downtime WHERE equipment_id = ?"
                        + " AND status = '" + Downtime.STATUS_ACTIVE + "'"
                        + " ORDER BY start_at ASC, downtime_id ASC",
                DOWNTIME_MAPPER, equipmentId);
    }

    /** 本轮生效停机区间：开始时刻不早于锚点时刻的 ACTIVE 区间；anchorSampledAt 为 null 时取全部。 */
    public List<Downtime> listActiveDowntimesForCycle(String equipmentId, Instant anchorSampledAt) {
        if (anchorSampledAt == null) {
            return listActiveDowntimes(equipmentId);
        }
        return jdbc.query(
                "SELECT " + DOWNTIME_COLUMNS + " FROM downtime WHERE equipment_id = ?"
                        + " AND status = '" + Downtime.STATUS_ACTIVE + "' AND start_at >= ?"
                        + " ORDER BY start_at ASC, downtime_id ASC",
                DOWNTIME_MAPPER, equipmentId, utc(anchorSampledAt));
    }

    /** 是否存在与新区间 [startAt, endAt) 重叠的生效区间（端点相接合法）。 */
    public boolean existsOverlappingActiveDowntime(String equipmentId, Instant startAt, Instant endAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM downtime WHERE equipment_id = ?"
                        + " AND status = '" + Downtime.STATUS_ACTIVE + "'"
                        + " AND start_at < ? AND end_at > ?",
                Integer.class, equipmentId, utc(endAt), utc(startAt));
        return count != null && count > 0;
    }

    /** 重算并更新生效区间的扣减量（读数新增/修订后调用；已撤销记录不可改写）。 */
    public void updateDowntimeDeduction(long downtimeId, long deductionMinutes) {
        jdbc.update("UPDATE downtime SET deduction_minutes = ? WHERE downtime_id = ?"
                        + " AND status = '" + Downtime.STATUS_ACTIVE + "'",
                deductionMinutes, downtimeId);
    }

    /** 撤销停机区间：状态置为 REVOKED 并记录撤销时刻与请求，原区间记录保留。 */
    public void revokeDowntime(long downtimeId, Instant revokedAt, String revokeRequestId) {
        jdbc.update("UPDATE downtime SET status = '" + Downtime.STATUS_REVOKED + "',"
                        + " revoked_at = ?, revoke_request_id = ? WHERE downtime_id = ?",
                utc(revokedAt), revokeRequestId, downtimeId);
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
