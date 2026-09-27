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
import com.example.starter.maintenance.domain.IsolationEvent;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.LockPoint;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.RejectedReading;

/**
 * 设备工时保养 + 能源隔离数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
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
            rs.getString("status"),
            rs.getObject("deactivated_at", OffsetDateTime.class) == null
                    ? null
                    : rs.getObject("deactivated_at", OffsetDateTime.class).toInstant());

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

    private static final RowMapper<IsolationOrder> ORDER_MAPPER = (rs, rowNum) -> new IsolationOrder(
            rs.getLong("order_id"),
            rs.getString("equipment_id"),
            rs.getString("status"),
            rs.getString("created_by"),
            readInstant(rs, "created_at"),
            rs.getObject("effective_at", OffsetDateTime.class) == null
                    ? null : rs.getObject("effective_at", OffsetDateTime.class).toInstant(),
            rs.getObject("released_at", OffsetDateTime.class) == null
                    ? null : rs.getObject("released_at", OffsetDateTime.class).toInstant(),
            rs.getString("released_by"),
            rs.getString("release_reason"));

    private static final RowMapper<LockPoint> LOCK_POINT_MAPPER = (rs, rowNum) -> new LockPoint(
            rs.getLong("order_id"),
            rs.getInt("ordinal"),
            rs.getString("lock_point_id"),
            rs.getString("description"),
            rs.getString("confirmed_isolator_by"),
            rs.getObject("confirmed_isolator_at", OffsetDateTime.class) == null
                    ? null : rs.getObject("confirmed_isolator_at", OffsetDateTime.class).toInstant(),
            rs.getString("confirmed_verifier_by"),
            rs.getObject("confirmed_verifier_at", OffsetDateTime.class) == null
                    ? null : rs.getObject("confirmed_verifier_at", OffsetDateTime.class).toInstant(),
            rs.getString("released_by"),
            rs.getObject("released_at", OffsetDateTime.class) == null
                    ? null : rs.getObject("released_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<IsolationEvent> EVENT_MAPPER = (rs, rowNum) -> new IsolationEvent(
            rs.getLong("event_id"),
            (Long) rs.getObject("order_id"),
            rs.getString("equipment_id"),
            rs.getString("event_type"),
            rs.getString("lock_point_id"),
            rs.getString("lock_role"),
            rs.getString("operator_id"),
            rs.getString("request_id"),
            rs.getString("detail"),
            readInstant(rs, "event_at"));

    private static final RowMapper<RejectedReading> REJECTED_MAPPER = (rs, rowNum) -> new RejectedReading(
            rs.getLong("rejected_id"),
            rs.getString("equipment_id"),
            rs.getLong("order_id"),
            rs.getString("request_id"),
            rs.getString("operator_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getLong("expected_version"),
            rs.getString("lock_point_ids"),
            rs.getString("reason_code"),
            readInstant(rs, "rejected_at"));

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes, version, created_at,"
                        + " status, deactivated_at) VALUES (?, ?, 1, ?, ?, NULL)",
                equipmentId, maintenancePeriodMinutes, utc(createdAt), Equipment.ACTIVE);
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, version, status, deactivated_at"
                        + " FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, version, status, deactivated_at"
                        + " FROM equipment WHERE equipment_id = ? FOR UPDATE",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
    }

    /** 停用设备（版本已由调用方递增）。 */
    public void deactivateEquipment(String equipmentId, Instant deactivatedAt) {
        jdbc.update("UPDATE equipment SET status = ?, deactivated_at = ? WHERE equipment_id = ?",
                Equipment.DEACTIVATED, utc(deactivatedAt), equipmentId);
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

    // ---------- 能源隔离令 ----------

    public long insertIsolationOrder(String equipmentId, String createdBy, String createdRequestId,
                                     Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO energy_isolation_order (equipment_id, status, created_by,"
                            + " created_request_id, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, IsolationOrder.PENDING);
            ps.setString(3, createdBy);
            ps.setString(4, createdRequestId);
            ps.setObject(5, utc(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("隔离令主键生成失败");
        }
        return key.longValue();
    }

    public Optional<IsolationOrder> findIsolationOrder(long orderId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, status, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM energy_isolation_order WHERE order_id = ?",
                ORDER_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    /** 设备当前活动隔离令：状态为 PENDING 或 EFFECTIVE 至多一条；均无时返回空。 */
    public Optional<IsolationOrder> findActiveOrder(String equipmentId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, status, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM energy_isolation_order WHERE equipment_id = ?"
                        + " AND status IN ('PENDING', 'EFFECTIVE')"
                        + " ORDER BY order_id DESC LIMIT 1",
                ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 设备最近一条隔离令（含已解除），按令标识倒序。 */
    public Optional<IsolationOrder> findLatestOrder(String equipmentId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, status, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM energy_isolation_order WHERE equipment_id = ?"
                        + " ORDER BY order_id DESC LIMIT 1",
                ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<IsolationOrder> listOrders(String equipmentId) {
        return jdbc.query(
                "SELECT order_id, equipment_id, status, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM energy_isolation_order WHERE equipment_id = ? ORDER BY order_id ASC",
                ORDER_MAPPER, equipmentId);
    }

    /** 全部锁定点双角色确认完成，令转为生效。 */
    public void markOrderEffective(long orderId, Instant effectiveAt) {
        jdbc.update("UPDATE energy_isolation_order SET status = ?, effective_at = ? WHERE order_id = ?",
                IsolationOrder.EFFECTIVE, utc(effectiveAt), orderId);
    }

    /** 全部锁定点逐一解除，令转为解除并固化解除人、原因。 */
    public void markOrderReleased(long orderId, String releasedBy, String releasedRequestId,
                                  String reason, Instant releasedAt) {
        jdbc.update("UPDATE energy_isolation_order SET status = ?, released_at = ?, released_by = ?,"
                        + " released_request_id = ?, release_reason = ? WHERE order_id = ?",
                IsolationOrder.RELEASED, utc(releasedAt), releasedBy, releasedRequestId, reason, orderId);
    }

    // ---------- 锁定点 ----------

    public void insertLockPoint(long orderId, int ordinal, String lockPointId, String description) {
        jdbc.update("INSERT INTO isolation_lock_point (order_id, ordinal, lock_point_id, description)"
                        + " VALUES (?, ?, ?, ?)",
                orderId, ordinal, lockPointId, description);
    }

    public List<LockPoint> listLockPoints(long orderId) {
        return jdbc.query(
                "SELECT order_id, ordinal, lock_point_id, description,"
                        + " confirmed_isolator_by, confirmed_isolator_at,"
                        + " confirmed_verifier_by, confirmed_verifier_at, released_by, released_at"
                        + " FROM isolation_lock_point WHERE order_id = ? ORDER BY ordinal ASC",
                LOCK_POINT_MAPPER, orderId);
    }

    public Optional<LockPoint> findLockPoint(long orderId, String lockPointId) {
        List<LockPoint> rows = jdbc.query(
                "SELECT order_id, ordinal, lock_point_id, description,"
                        + " confirmed_isolator_by, confirmed_isolator_at,"
                        + " confirmed_verifier_by, confirmed_verifier_at, released_by, released_at"
                        + " FROM isolation_lock_point WHERE order_id = ? AND lock_point_id = ?",
                LOCK_POINT_MAPPER, orderId, lockPointId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一锁定点的确认/解除串行化。 */
    public Optional<LockPoint> findLockPointForUpdate(long orderId, String lockPointId) {
        List<LockPoint> rows = jdbc.query(
                "SELECT order_id, ordinal, lock_point_id, description,"
                        + " confirmed_isolator_by, confirmed_isolator_at,"
                        + " confirmed_verifier_by, confirmed_verifier_at, released_by, released_at"
                        + " FROM isolation_lock_point WHERE order_id = ? AND lock_point_id = ? FOR UPDATE",
                LOCK_POINT_MAPPER, orderId, lockPointId);
        return rows.stream().findFirst();
    }

    /** 固化某角色对锁定点的确认操作人与请求；要求该角色此前未确认（由唯一语义保证）。 */
    public void confirmLockPoint(long orderId, String lockPointId, String role,
                                 String operatorId, String requestId, Instant confirmedAt) {
        if (LockPoint.ROLE_ISOLATOR.equals(role)) {
            jdbc.update("UPDATE isolation_lock_point SET confirmed_isolator_by = ?,"
                            + " confirmed_isolator_request_id = ?, confirmed_isolator_at = ?"
                            + " WHERE order_id = ? AND lock_point_id = ?",
                    operatorId, requestId, utc(confirmedAt), orderId, lockPointId);
        } else {
            jdbc.update("UPDATE isolation_lock_point SET confirmed_verifier_by = ?,"
                            + " confirmed_verifier_request_id = ?, confirmed_verifier_at = ?"
                            + " WHERE order_id = ? AND lock_point_id = ?",
                    operatorId, requestId, utc(confirmedAt), orderId, lockPointId);
        }
    }

    /** 逐点解除：写入解除操作人、请求与时刻。 */
    public void releaseLockPoint(long orderId, String lockPointId, String operatorId,
                                 String requestId, Instant releasedAt) {
        jdbc.update("UPDATE isolation_lock_point SET released_by = ?, released_request_id = ?,"
                        + " released_at = ? WHERE order_id = ? AND lock_point_id = ?",
                operatorId, requestId, utc(releasedAt), orderId, lockPointId);
    }

    // ---------- 隔离事件 ----------

    public void insertEvent(Long orderId, String equipmentId, String eventType, String lockPointId,
                            String lockRole, String operatorId, String requestId, String detail,
                            Instant eventAt) {
        jdbc.update("INSERT INTO isolation_event (order_id, equipment_id, event_type, lock_point_id,"
                        + " lock_role, operator_id, request_id, detail, event_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, equipmentId, eventType, lockPointId, lockRole, operatorId, requestId,
                detail, utc(eventAt));
    }

    public List<IsolationEvent> listEventsByOrder(long orderId) {
        return jdbc.query(
                "SELECT event_id, order_id, equipment_id, event_type, lock_point_id, lock_role,"
                        + " operator_id, request_id, detail, event_at"
                        + " FROM isolation_event WHERE order_id = ? ORDER BY event_id ASC",
                EVENT_MAPPER, orderId);
    }

    public List<IsolationEvent> listEventsByEquipment(String equipmentId) {
        return jdbc.query(
                "SELECT event_id, order_id, equipment_id, event_type, lock_point_id, lock_role,"
                        + " operator_id, request_id, detail, event_at"
                        + " FROM isolation_event WHERE equipment_id = ? ORDER BY event_id ASC",
                EVENT_MAPPER, equipmentId);
    }

    // ---------- 被拒读数证据 ----------

    public void insertRejectedReading(RejectedReading rejected) {
        jdbc.update("INSERT INTO rejected_reading (equipment_id, order_id, request_id, operator_id,"
                        + " reading_id, sampled_at, cumulative_minutes, expected_version,"
                        + " lock_point_ids, reason_code, rejected_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rejected.equipmentId(), rejected.orderId(), rejected.requestId(), rejected.operatorId(),
                rejected.readingId(), utc(rejected.sampledAt()), rejected.cumulativeMinutes(),
                rejected.expectedVersion(), rejected.lockPointIds(), rejected.reasonCode(),
                utc(rejected.rejectedAt()));
    }

    public List<RejectedReading> listRejectedReadings(String equipmentId) {
        return jdbc.query(
                "SELECT rejected_id, equipment_id, order_id, request_id, operator_id, reading_id,"
                        + " sampled_at, cumulative_minutes, expected_version, lock_point_ids,"
                        + " reason_code, rejected_at"
                        + " FROM rejected_reading WHERE equipment_id = ? ORDER BY rejected_id ASC",
                REJECTED_MAPPER, equipmentId);
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
