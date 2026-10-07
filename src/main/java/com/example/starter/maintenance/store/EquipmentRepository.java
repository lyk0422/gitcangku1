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
import com.example.starter.maintenance.domain.StressCycle;

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
            (Long) rs.getObject("fatigue_threshold"),
            (Long) rs.getObject("baseline_stress_mpa"),
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

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes,
                                Long fatigueThreshold, Long baselineStressMpa, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes,"
                        + " fatigue_threshold, baseline_stress_mpa, version, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                equipmentId, maintenancePeriodMinutes, fatigueThreshold, baselineStressMpa,
                utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, fatigue_threshold,"
                        + " baseline_stress_mpa, version FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, fatigue_threshold,"
                        + " baseline_stress_mpa, version FROM equipment"
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

    // ---------- 闭合应力循环 ----------

    private static final RowMapper<StressCycle> CYCLE_MAPPER = (rs, rowNum) -> new StressCycle(
            rs.getString("equipment_id"),
            rs.getString("cycle_id"),
            rs.getString("operator"),
            rs.getLong("rise_stress_mpa"),
            readInstant(rs, "rise_sampled_at"),
            rs.getString("rise_reading_id"),
            rs.getInt("rise_reading_revision_no"),
            rs.getLong("fall_stress_mpa"),
            readInstant(rs, "fall_sampled_at"),
            rs.getString("fall_reading_id"),
            rs.getInt("fall_reading_revision_no"),
            rs.getLong("damage"));

    private static final String CYCLE_COLUMNS = "equipment_id, cycle_id, operator,"
            + " rise_stress_mpa, rise_sampled_at, rise_reading_id, rise_reading_revision_no,"
            + " fall_stress_mpa, fall_sampled_at, fall_reading_id, fall_reading_revision_no, damage";

    public void insertCycle(StressCycle cycle, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO stress_cycle (equipment_id, cycle_id, operator,"
                        + " rise_stress_mpa, rise_sampled_at, rise_reading_id, rise_reading_revision_no,"
                        + " fall_stress_mpa, fall_sampled_at, fall_reading_id, fall_reading_revision_no,"
                        + " damage, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cycle.equipmentId(), cycle.cycleId(), cycle.operator(),
                cycle.riseStressMpa(), utc(cycle.riseSampledAt()), cycle.riseReadingId(),
                cycle.riseReadingRevisionNo(),
                cycle.fallStressMpa(), utc(cycle.fallSampledAt()), cycle.fallReadingId(),
                cycle.fallReadingRevisionNo(),
                cycle.damage(), requestId, utc(createdAt));
    }

    public Optional<StressCycle> findCycle(String equipmentId, String cycleId) {
        List<StressCycle> rows = jdbc.query(
                "SELECT " + CYCLE_COLUMNS + " FROM stress_cycle"
                        + " WHERE equipment_id = ? AND cycle_id = ?",
                CYCLE_MAPPER, equipmentId, cycleId);
        return rows.stream().findFirst();
    }

    /** 全部循环按升峰时刻升序（账本顺序）。 */
    public List<StressCycle> listCycles(String equipmentId) {
        return jdbc.query(
                "SELECT " + CYCLE_COLUMNS + " FROM stress_cycle WHERE equipment_id = ?"
                        + " ORDER BY rise_sampled_at ASC, cycle_id ASC",
                CYCLE_MAPPER, equipmentId);
    }

    /** 是否存在与新区间 [riseAt, fallAt] 重叠的循环（端点相接不算重叠）。 */
    public boolean existsOverlappingCycle(String equipmentId, Instant riseAt, Instant fallAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stress_cycle WHERE equipment_id = ?"
                        + " AND rise_sampled_at < ? AND fall_sampled_at > ?",
                Integer.class, equipmentId, utc(fallAt), utc(riseAt));
        return count != null && count > 0;
    }

    /** 是否存在跨越锚点时刻的完整循环（升峰早于锚点且回落晚于锚点）。 */
    public boolean existsCycleSpanningAnchor(String equipmentId, Instant anchorSampledAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stress_cycle WHERE equipment_id = ?"
                        + " AND rise_sampled_at < ? AND fall_sampled_at > ?",
                Integer.class, equipmentId, utc(anchorSampledAt), utc(anchorSampledAt));
        return count != null && count > 0;
    }

    /** 是否存在保养锚点严格落在新区间内部（新循环跨历史锚点）。 */
    public boolean existsAnchorBetween(String equipmentId, Instant riseAt, Instant fallAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ?"
                        + " AND anchor_sampled_at > ? AND anchor_sampled_at < ?",
                Integer.class, equipmentId, utc(riseAt), utc(fallAt));
        return count != null && count > 0;
    }

    /** 该读数是否已被任意应力循环引用（引用后不可修订）。 */
    public boolean existsCycleReferencingReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stress_cycle WHERE equipment_id = ?"
                        + " AND (rise_reading_id = ? OR fall_reading_id = ?)",
                Integer.class, equipmentId, readingId, readingId);
        return count != null && count > 0;
    }

    /**
     * 当前轮累计损伤：升峰时刻不早于最近保养锚点的循环损伤之和；since 为 null 时统计全部。
     * 锚点之前（回落时刻不晚于锚点）的循环只归历史轮，不计入当前轮。
     */
    public long sumCurrentRoundDamage(String equipmentId, Instant since) {
        Long sum;
        if (since == null) {
            sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(damage), 0) FROM stress_cycle WHERE equipment_id = ?",
                    Long.class, equipmentId);
        } else {
            sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(damage), 0) FROM stress_cycle"
                            + " WHERE equipment_id = ? AND rise_sampled_at >= ?",
                    Long.class, equipmentId, utc(since));
        }
        return sum != null ? sum : 0L;
    }
}
