package com.example.starter.maintenance.store;

import java.math.BigDecimal;
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

import com.example.starter.maintenance.domain.ConversionRecord;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MeasurementUnit;
import com.example.starter.maintenance.domain.Reading;

/**
 * 设备工时保养数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 * 读数值以设备登记单位十进制存储（cumulative_value），并冗余换算分钟数（cumulative_minutes）。
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
            MeasurementUnit.valueOf(rs.getString("measurement_unit")),
            rs.getBigDecimal("maintenance_period_value"),
            rs.getLong("maintenance_period_minutes"),
            rs.getLong("version"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getBigDecimal("cumulative_value"),
            rs.getLong("cumulative_minutes"),
            rs.getInt("revision_no"));

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) -> new MaintenanceRecord(
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("anchor_revision_no"),
            readInstant(rs, "anchor_sampled_at"),
            rs.getBigDecimal("anchor_cumulative_value"),
            rs.getLong("anchor_cumulative_minutes"),
            readInstant(rs, "completed_at"));

    private static final RowMapper<ConversionRecord> CONVERSION_MAPPER = (rs, rowNum) -> new ConversionRecord(
            rs.getLong("conversion_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("revision_no"),
            MeasurementUnit.valueOf(rs.getString("source_unit")),
            rs.getBigDecimal("source_value"),
            rs.getBigDecimal("converted_value"),
            rs.getLong("converted_minutes"),
            rs.getString("request_id"),
            readInstant(rs, "created_at"));

    // ---------- 设备 ----------

    public void insertEquipment(Equipment equipment, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, measurement_unit, maintenance_period_value,"
                        + " maintenance_period_minutes, version, created_at) VALUES (?, ?, ?, ?, 1, ?)",
                equipment.equipmentId(), equipment.measurementUnit().name(),
                equipment.maintenancePeriodValue(), equipment.maintenancePeriodMinutes(),
                utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, measurement_unit, maintenance_period_value,"
                        + " maintenance_period_minutes, version FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, measurement_unit, maintenance_period_value,"
                        + " maintenance_period_minutes, version FROM equipment"
                        + " WHERE equipment_id = ? FOR UPDATE",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
    }

    // ---------- 读数 ----------

    public void insertReading(Reading reading, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, sampled_at, cumulative_value,"
                        + " cumulative_minutes, revision_no, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                reading.equipmentId(), reading.readingId(), utc(reading.sampledAt()),
                reading.cumulativeValue(), reading.cumulativeMinutes(), reading.revisionNo(),
                utc(createdAt), utc(createdAt));
    }

    public Optional<Reading> findReading(String equipmentId, String readingId) {
        List<Reading> rows = jdbc.query(readingColumns() + " WHERE equipment_id = ? AND reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                readingColumns() + " WHERE equipment_id = ? AND sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                readingColumns() + " WHERE equipment_id = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                readingColumns() + " WHERE equipment_id = ? AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    public Optional<Reading> findLatestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                readingColumns() + " WHERE equipment_id = ? ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void updateReadingValue(String equipmentId, String readingId, BigDecimal cumulativeValue,
                                   long cumulativeMinutes, int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_value = ?, cumulative_minutes = ?,"
                        + " revision_no = ?, updated_at = ? WHERE equipment_id = ? AND reading_id = ?",
                cumulativeValue, cumulativeMinutes, newRevisionNo, utc(updatedAt),
                equipmentId, readingId);
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                readingColumns() + " WHERE equipment_id = ?"
                        + " ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    private static String readingColumns() {
        return "SELECT equipment_id, reading_id, sampled_at, cumulative_value, cumulative_minutes,"
                + " revision_no FROM reading";
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               BigDecimal cumulativeValue, long cumulativeMinutes,
                               String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_value, cumulative_minutes, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeValue, cumulativeMinutes,
                requestId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_value, cumulative_minutes, request_id, created_at"
                        + " FROM reading_revision WHERE equipment_id = ? AND reading_id = ?"
                        + " ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getBigDecimal("cumulative_value"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, BigDecimal cumulativeValue, long cumulativeMinutes,
                              String requestId, Instant createdAt) {
    }

    // ---------- 保养记录 ----------

    public long insertMaintenance(String equipmentId, String readingId, int anchorRevisionNo,
                                  Instant anchorSampledAt, BigDecimal anchorCumulativeValue,
                                  long anchorCumulativeMinutes, String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_cumulative_value, anchor_cumulative_minutes,"
                            + " request_id, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setInt(3, anchorRevisionNo);
            ps.setObject(4, utc(anchorSampledAt));
            ps.setBigDecimal(5, anchorCumulativeValue);
            ps.setLong(6, anchorCumulativeMinutes);
            ps.setString(7, requestId);
            ps.setObject(8, utc(completedAt));
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
                maintenanceColumns() + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                maintenanceColumns() + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at ASC, maintenance_id ASC",
                MAINTENANCE_MAPPER, equipmentId);
    }

    private static String maintenanceColumns() {
        return "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no, anchor_sampled_at,"
                + " anchor_cumulative_value, anchor_cumulative_minutes, completed_at";
    }

    /** 该读数是否已被任意历史保养记录锚定（锚定后不可修订）。 */
    public boolean existsMaintenanceAnchoringReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ? AND reading_id = ?",
                Integer.class, equipmentId, readingId);
        return count != null && count > 0;
    }

    // ---------- 读数单位换算留痕 ----------

    public void insertConversion(ConversionRecord record) {
        jdbc.update("INSERT INTO reading_conversion (equipment_id, reading_id, revision_no, source_unit,"
                        + " source_value, converted_value, converted_minutes, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.equipmentId(), record.readingId(), record.revisionNo(),
                record.sourceUnit().name(), record.sourceValue(), record.convertedValue(),
                record.convertedMinutes(), record.requestId(), utc(record.createdAt()));
    }

    /** 换算留痕：按设备只读查询，稳定排序（自增主键升序）。 */
    public List<ConversionRecord> listConversions(String equipmentId) {
        return jdbc.query(
                "SELECT conversion_id, equipment_id, reading_id, revision_no, source_unit, source_value,"
                        + " converted_value, converted_minutes, request_id, created_at"
                        + " FROM reading_conversion WHERE equipment_id = ?"
                        + " ORDER BY conversion_id ASC",
                CONVERSION_MAPPER, equipmentId);
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
