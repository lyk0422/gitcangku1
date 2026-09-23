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

import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Meter;
import com.example.starter.maintenance.domain.Reading;

/**
 * 设备工时保养数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取；
 * 工时以小时 DECIMAL(22,6) 存储，分钟为换算缓存。
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

    private static final RowMapper<Meter> METER_MAPPER = (rs, rowNum) -> {
        OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
        return new Meter(
                rs.getString("equipment_id"),
                rs.getString("meter_key"),
                rs.getString("status"),
                rs.getInt("seq_no"),
                rs.getString("predecessor_key"),
                rs.getString("replacement_key"),
                rs.getBigDecimal("initial_raw_hours"),
                rs.getBigDecimal("final_raw_hours"),
                rs.getBigDecimal("offset_hours"),
                rs.getInt("recalc_version"),
                readInstant(rs, "created_at"),
                closedAt == null ? null : closedAt.toInstant());
    };

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getString("meter_key"),
            readInstant(rs, "sampled_at"),
            rs.getBigDecimal("raw_hours"),
            rs.getBigDecimal("virtual_hours"),
            rs.getInt("revision_no"));

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) ->
            new MaintenanceRecord(
                    rs.getLong("maintenance_id"),
                    rs.getString("equipment_id"),
                    rs.getString("reading_id"),
                    rs.getInt("anchor_revision_no"),
                    readInstant(rs, "anchor_sampled_at"),
                    rs.getBigDecimal("anchor_virtual_hours"),
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

    // ---------- 工时表 ----------

    public void insertMeter(Meter meter) {
        jdbc.update("INSERT INTO meter (equipment_id, meter_key, status, seq_no, predecessor_key,"
                        + " replacement_key, initial_raw_hours, final_raw_hours, offset_hours,"
                        + " recalc_version, created_at, closed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                meter.equipmentId(), meter.meterKey(), meter.status(), meter.seqNo(),
                meter.predecessorKey(), meter.replacementKey(), meter.initialRawHours(),
                meter.finalRawHours(), meter.offsetHours(), meter.recalcVersion(),
                utc(meter.createdAt()), meter.closedAt() == null ? null : utc(meter.closedAt()));
    }

    public Optional<Meter> findMeter(String equipmentId, String meterKey) {
        List<Meter> rows = jdbc.query(meterSelect() + " WHERE equipment_id = ? AND meter_key = ?",
                METER_MAPPER, equipmentId, meterKey);
        return rows.stream().findFirst();
    }

    /** 按全局唯一 meterKey 查找（更换登记时校验新表 meterKey 全局唯一）。 */
    public Optional<Meter> findMeterByGlobalKey(String meterKey) {
        List<Meter> rows = jdbc.query(meterSelect() + " WHERE meter_key = ?",
                METER_MAPPER, meterKey);
        return rows.stream().findFirst();
    }

    public Optional<Meter> findActiveMeter(String equipmentId) {
        List<Meter> rows = jdbc.query(
                meterSelect() + " WHERE equipment_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY seq_no DESC LIMIT 1",
                METER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<Meter> listMeters(String equipmentId) {
        return jdbc.query(meterSelect() + " WHERE equipment_id = ? ORDER BY seq_no ASC",
                METER_MAPPER, equipmentId);
    }

    /** 关表：写入最终原始读数与关闭时刻，状态置为 CLOSED。 */
    public void closeMeter(String equipmentId, String meterKey, BigDecimal finalRawHours, Instant closedAt) {
        jdbc.update("UPDATE meter SET status = 'CLOSED', final_raw_hours = ?, closed_at = ?"
                        + " WHERE equipment_id = ? AND meter_key = ?",
                finalRawHours, utc(closedAt), equipmentId, meterKey);
    }

    /** 跨表重算：刷新后继表冻结偏移并重算版本加一。 */
    public void updateMeterOffset(String equipmentId, String meterKey, BigDecimal offsetHours) {
        jdbc.update("UPDATE meter SET offset_hours = ?, recalc_version = recalc_version + 1"
                        + " WHERE equipment_id = ? AND meter_key = ?",
                offsetHours, equipmentId, meterKey);
    }

    private static String meterSelect() {
        return "SELECT equipment_id, meter_key, status, seq_no, predecessor_key, replacement_key,"
                + " initial_raw_hours, final_raw_hours, offset_hours, recalc_version, created_at, closed_at"
                + " FROM meter";
    }

    // ---------- 读数 ----------

    public void insertReading(Reading reading, long cumulativeMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, meter_key, sampled_at, raw_hours,"
                        + " virtual_hours, cumulative_minutes, revision_no, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                reading.equipmentId(), reading.readingId(), reading.meterKey(), utc(reading.sampledAt()),
                reading.rawHours(), reading.virtualHours(), cumulativeMinutes,
                utc(createdAt), utc(createdAt));
    }

    public Optional<Reading> findReading(String equipmentId, String readingId) {
        List<Reading> rows = jdbc.query(readingSelect() + " WHERE r.equipment_id = ? AND r.reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                readingSelect() + " WHERE r.equipment_id = ? AND r.sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 同一工时表内采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReadingInMeter(String meterKey, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                readingSelect() + " WHERE r.meter_key = ? AND r.sampled_at < ?"
                        + " ORDER BY r.sampled_at DESC LIMIT 1",
                READING_MAPPER, meterKey, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 同一工时表内采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReadingInMeter(String meterKey, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                readingSelect() + " WHERE r.meter_key = ? AND r.sampled_at > ?"
                        + " ORDER BY r.sampled_at ASC LIMIT 1",
                READING_MAPPER, meterKey, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 该工时表最后一条（采样时刻最大）读数；无读数时为空。 */
    public Optional<Reading> findLatestReadingInMeter(String meterKey) {
        List<Reading> rows = jdbc.query(
                readingSelect() + " WHERE r.meter_key = ?"
                        + " ORDER BY r.sampled_at DESC, r.reading_id DESC LIMIT 1",
                READING_MAPPER, meterKey);
        return rows.stream().findFirst();
    }

    /** 设备全链最新读数（跨表按采样时刻取最大）。 */
    public Optional<Reading> findLatestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                readingSelect() + " WHERE r.equipment_id = ?"
                        + " ORDER BY r.sampled_at DESC, r.reading_id DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 指定工时表全部读数（按采样时刻升序），用于跨表重算。 */
    public List<Reading> listReadingsOfMeter(String meterKey) {
        return jdbc.query(readingSelect() + " WHERE r.meter_key = ?"
                        + " ORDER BY r.sampled_at ASC, r.reading_id ASC",
                READING_MAPPER, meterKey);
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                readingSelect() + " WHERE r.equipment_id = ?"
                        + " ORDER BY r.sampled_at ASC, r.reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    /** 修订：更新表内原始工时及其映射后的虚拟工时/分钟缓存。 */
    public void updateReadingValue(String equipmentId, String readingId, BigDecimal rawHours,
                                   BigDecimal virtualHours, long cumulativeMinutes,
                                   int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET raw_hours = ?, virtual_hours = ?, cumulative_minutes = ?,"
                        + " revision_no = ?, updated_at = ? WHERE equipment_id = ? AND reading_id = ?",
                rawHours, virtualHours, cumulativeMinutes, newRevisionNo, utc(updatedAt),
                equipmentId, readingId);
    }

    /** 跨表重算：raw 不变，仅刷新偏移变化后的虚拟工时与分钟缓存。 */
    public void updateReadingVirtual(String equipmentId, String readingId, BigDecimal virtualHours,
                                     long cumulativeMinutes) {
        jdbc.update("UPDATE reading SET virtual_hours = ?, cumulative_minutes = ?"
                        + " WHERE equipment_id = ? AND reading_id = ?",
                virtualHours, cumulativeMinutes, equipmentId, readingId);
    }

    private static String readingSelect() {
        return "SELECT r.equipment_id AS equipment_id, r.reading_id AS reading_id, r.meter_key AS meter_key,"
                + " r.sampled_at AS sampled_at, r.raw_hours AS raw_hours, r.virtual_hours AS virtual_hours,"
                + " r.revision_no AS revision_no FROM reading r";
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               BigDecimal rawHours, long cumulativeMinutes, String requestId,
                               Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no, raw_hours,"
                        + " cumulative_minutes, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, rawHours, cumulativeMinutes, requestId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, raw_hours, cumulative_minutes, request_id, created_at"
                        + " FROM reading_revision WHERE equipment_id = ? AND reading_id = ?"
                        + " ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getBigDecimal("raw_hours"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, BigDecimal rawHours, long cumulativeMinutes,
                              String requestId, Instant createdAt) {
    }

    // ---------- 保养记录 ----------

    public long insertMaintenance(String equipmentId, String readingId, int anchorRevisionNo,
                                  Instant anchorSampledAt, BigDecimal anchorVirtualHours,
                                  String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_virtual_hours, anchor_cumulative_minutes,"
                            + " request_id, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setInt(3, anchorRevisionNo);
            ps.setObject(4, utc(anchorSampledAt));
            ps.setBigDecimal(5, anchorVirtualHours);
            ps.setLong(6, toMinutes(anchorVirtualHours));
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

    /** 跨表重算：刷新锚点虚拟工时快照及其分钟缓存。 */
    public void updateMaintenanceAnchorVirtual(long maintenanceId, BigDecimal anchorVirtualHours) {
        jdbc.update("UPDATE maintenance SET anchor_virtual_hours = ?, anchor_cumulative_minutes = ?"
                        + " WHERE maintenance_id = ?",
                anchorVirtualHours, toMinutes(anchorVirtualHours), maintenanceId);
    }

    /** 最近一次保养（锚点时间最大者；锚点时间严格递增，故唯一）。 */
    public Optional<MaintenanceRecord> findLastMaintenance(String equipmentId) {
        List<MaintenanceRecord> rows = jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_virtual_hours, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_virtual_hours, completed_at"
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

    // ---------- 跨表重算审计 ----------

    public int nextRecomputeNo(String equipmentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(recompute_no), 0) FROM meter_chain_recompute WHERE equipment_id = ?",
                Integer.class, equipmentId);
        return (max == null ? 0 : max) + 1;
    }

    public void insertRecompute(String equipmentId, int recomputeNo, String triggeredMeterKey,
                                String triggeredReadingId, String requestId,
                                BigDecimal fromRawHours, BigDecimal toRawHours, Instant createdAt) {
        jdbc.update("INSERT INTO meter_chain_recompute (equipment_id, recompute_no, triggered_meter_key,"
                        + " triggered_reading_id, request_id, from_raw_hours, to_raw_hours, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                equipmentId, recomputeNo, triggeredMeterKey, triggeredReadingId, requestId,
                fromRawHours, toRawHours, utc(createdAt));
    }

    public List<RecomputeRow> listRecomputes(String equipmentId) {
        return jdbc.query(
                "SELECT recompute_no, triggered_meter_key, triggered_reading_id, request_id,"
                        + " from_raw_hours, to_raw_hours, created_at FROM meter_chain_recompute"
                        + " WHERE equipment_id = ? ORDER BY recompute_no ASC",
                (rs, rowNum) -> new RecomputeRow(
                        rs.getInt("recompute_no"),
                        rs.getString("triggered_meter_key"),
                        rs.getString("triggered_reading_id"),
                        rs.getString("request_id"),
                        rs.getBigDecimal("from_raw_hours"),
                        rs.getBigDecimal("to_raw_hours"),
                        readInstant(rs, "created_at")),
                equipmentId);
    }

    public record RecomputeRow(int recomputeNo, String triggeredMeterKey, String triggeredReadingId,
                               String requestId, BigDecimal fromRawHours, BigDecimal toRawHours,
                               Instant createdAt) {
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

    // ---------- 工具 ----------

    private static long toMinutes(BigDecimal hours) {
        return hours.multiply(BigDecimal.valueOf(60))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }
}
