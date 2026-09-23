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
import com.example.starter.maintenance.domain.Meter;
import com.example.starter.maintenance.domain.MeterReplacement;
import com.example.starter.maintenance.domain.Reading;

/**
 * 设备工时保养数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 * 读数、保养锚点均归属具体工时表；虚拟工时 = 原始值 + 所属表 offset，由服务层换算。
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
            rs.getLong("recalc_version"));

    private static final RowMapper<Meter> METER_MAPPER = (rs, rowNum) -> new Meter(
            rs.getString("meter_key"),
            rs.getString("equipment_id"),
            rs.getInt("chain_seq"),
            rs.getString("status"),
            rs.getLong("initial_raw_hours"),
            (Long) rs.getObject("final_raw_hours"),
            rs.getLong("offset_hours"));

    private static final RowMapper<MeterReplacement> REPLACEMENT_MAPPER = (rs, rowNum) -> new MeterReplacement(
            rs.getString("replacement_key"),
            rs.getString("equipment_id"),
            rs.getString("old_meter_key"),
            rs.getString("new_meter_key"),
            rs.getString("old_last_reading_id"),
            rs.getInt("old_last_reading_version"),
            rs.getLong("final_raw_hours"),
            rs.getLong("initial_raw_hours"),
            rs.getLong("offset_hours"),
            rs.getString("request_id"),
            readInstant(rs, "created_at"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getString("meter_key"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getInt("revision_no"));

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) -> new MaintenanceRecord(
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getString("meter_key"),
            rs.getInt("anchor_revision_no"),
            readInstant(rs, "anchor_sampled_at"),
            rs.getLong("anchor_cumulative_minutes"),
            rs.getLong("anchor_virtual_hours"),
            readInstant(rs, "completed_at"));

    private static final String EQUIPMENT_COLUMNS =
            "equipment_id, maintenance_period_minutes, version, recalc_version";
    private static final String METER_COLUMNS =
            "meter_key, equipment_id, chain_seq, status, initial_raw_hours, final_raw_hours, offset_hours";
    private static final String READING_COLUMNS =
            "equipment_id, reading_id, meter_key, sampled_at, cumulative_minutes, revision_no";
    private static final String MAINTENANCE_COLUMNS =
            "maintenance_id, equipment_id, reading_id, meter_key, anchor_revision_no,"
                    + " anchor_sampled_at, anchor_cumulative_minutes, anchor_virtual_hours, completed_at";
    private static final String REPLACEMENT_COLUMNS =
            "replacement_key, equipment_id, old_meter_key, new_meter_key, old_last_reading_id,"
                    + " old_last_reading_version, final_raw_hours, initial_raw_hours, offset_hours,"
                    + " request_id, created_at";

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes, version, recalc_version,"
                        + " created_at) VALUES (?, ?, 1, 0, ?)",
                equipmentId, maintenancePeriodMinutes, utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT " + EQUIPMENT_COLUMNS + " FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验、业务变更与链式重算原子。 */
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

    /** 链式重算版本号加一：仅在已关闭表最后有效读数被修订并触发全链重算时调用。 */
    public void incrementRecalcVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET recalc_version = recalc_version + 1 WHERE equipment_id = ?",
                equipmentId);
    }

    // ---------- 工时表 ----------

    public void insertMeter(Meter meter, Instant createdAt) {
        jdbc.update("INSERT INTO meter (meter_key, equipment_id, chain_seq, status, initial_raw_hours,"
                        + " final_raw_hours, offset_hours, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                meter.meterKey(), meter.equipmentId(), meter.chainSeq(), meter.status(),
                meter.initialRawHours(), meter.finalRawHours(), meter.offsetHours(), utc(createdAt));
    }

    public Optional<Meter> findMeter(String meterKey) {
        List<Meter> rows = jdbc.query(
                "SELECT " + METER_COLUMNS + " FROM meter WHERE meter_key = ?",
                METER_MAPPER, meterKey);
        return rows.stream().findFirst();
    }

    public Optional<Meter> findActiveMeter(String equipmentId) {
        List<Meter> rows = jdbc.query(
                "SELECT " + METER_COLUMNS + " FROM meter WHERE equipment_id = ? AND status = 'ACTIVE'",
                METER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 设备更换链全部工时表，按链内序号升序。 */
    public List<Meter> listMeters(String equipmentId) {
        return jdbc.query(
                "SELECT " + METER_COLUMNS + " FROM meter WHERE equipment_id = ? ORDER BY chain_seq ASC",
                METER_MAPPER, equipmentId);
    }

    /** 关闭工时表：置 CLOSED 并记录申报的最终原始读数。 */
    public void closeMeter(String meterKey, long finalRawHours) {
        jdbc.update("UPDATE meter SET status = 'CLOSED', final_raw_hours = ? WHERE meter_key = ?",
                finalRawHours, meterKey);
    }

    /** 链式重算：更新后继表的冻结 offset。 */
    public void updateMeterOffset(String meterKey, long newOffsetHours) {
        jdbc.update("UPDATE meter SET offset_hours = ? WHERE meter_key = ?", newOffsetHours, meterKey);
    }

    // ---------- 工时表更换记录 ----------

    public void insertReplacement(MeterReplacement replacement) {
        jdbc.update("INSERT INTO meter_replacement (replacement_key, equipment_id, old_meter_key,"
                        + " new_meter_key, old_last_reading_id, old_last_reading_version, final_raw_hours,"
                        + " initial_raw_hours, offset_hours, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                replacement.replacementKey(), replacement.equipmentId(), replacement.oldMeterKey(),
                replacement.newMeterKey(), replacement.oldLastReadingId(), replacement.oldLastReadingVersion(),
                replacement.finalRawHours(), replacement.initialRawHours(), replacement.offsetHours(),
                replacement.requestId(), utc(replacement.createdAt()));
    }

    public Optional<MeterReplacement> findReplacement(String replacementKey) {
        List<MeterReplacement> rows = jdbc.query(
                "SELECT " + REPLACEMENT_COLUMNS + " FROM meter_replacement WHERE replacement_key = ?",
                REPLACEMENT_MAPPER, replacementKey);
        return rows.stream().findFirst();
    }

    /** 设备全部更换记录，按登记时刻升序（与链序一致）。 */
    public List<MeterReplacement> listReplacements(String equipmentId) {
        return jdbc.query(
                "SELECT " + REPLACEMENT_COLUMNS + " FROM meter_replacement WHERE equipment_id = ?"
                        + " ORDER BY created_at ASC, replacement_key ASC",
                REPLACEMENT_MAPPER, equipmentId);
    }

    // ---------- 读数 ----------

    public void insertReading(Reading reading, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, meter_key, sampled_at, cumulative_minutes,"
                        + " revision_no, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                reading.equipmentId(), reading.readingId(), reading.meterKey(), utc(reading.sampledAt()),
                reading.cumulativeMinutes(), utc(createdAt), utc(createdAt));
    }

    public Optional<Reading> findReading(String equipmentId, String readingId) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS + " FROM reading WHERE equipment_id = ? AND reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS + " FROM reading WHERE equipment_id = ? AND sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 同一工时表内采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReading(String meterKey, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS + " FROM reading WHERE meter_key = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, meterKey, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 同一工时表内采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReading(String meterKey, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS + " FROM reading WHERE meter_key = ? AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, meterKey, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 指定工时表的最后有效读数（采样时刻最大者）。 */
    public Optional<Reading> findLatestReadingOnMeter(String meterKey) {
        List<Reading> rows = jdbc.query(
                "SELECT " + READING_COLUMNS + " FROM reading WHERE meter_key = ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, meterKey);
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
                "SELECT " + READING_COLUMNS + " FROM reading WHERE equipment_id = ?"
                        + " ORDER BY sampled_at ASC, reading_id ASC",
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

    public long insertMaintenance(String equipmentId, String readingId, String meterKey, int anchorRevisionNo,
                                  Instant anchorSampledAt, long anchorCumulativeMinutes, long anchorVirtualHours,
                                  String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, meter_key, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_cumulative_minutes, anchor_virtual_hours,"
                            + " request_id, completed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setString(3, meterKey);
            ps.setInt(4, anchorRevisionNo);
            ps.setObject(5, utc(anchorSampledAt));
            ps.setLong(6, anchorCumulativeMinutes);
            ps.setLong(7, anchorVirtualHours);
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
                "SELECT " + MAINTENANCE_COLUMNS + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                "SELECT " + MAINTENANCE_COLUMNS + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at ASC, maintenance_id ASC",
                MAINTENANCE_MAPPER, equipmentId);
    }

    /** 链式重算：将某工时表上全部保养锚点的虚拟工时按新 offset 重算（原始快照不变）。 */
    public void recomputeAnchorVirtualHours(String meterKey, long newOffsetHours) {
        jdbc.update("UPDATE maintenance SET anchor_virtual_hours = anchor_cumulative_minutes + ?"
                        + " WHERE meter_key = ?",
                newOffsetHours, meterKey);
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
