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

import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.LockEvent;
import com.example.starter.maintenance.domain.RejectedReading;

/**
 * 能源隔离令数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 * 锁定点快照、事件流水与被拒读数证据只追加不更新；状态迁移只更新隔离令行与锁定点解除列。
 */
@Repository
public class IsolationRepository {

    private final JdbcTemplate jdbc;

    public IsolationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<IsolationOrder> ORDER_MAPPER = (rs, rowNum) -> new IsolationOrder(
            rs.getString("order_id"),
            rs.getString("equipment_id"),
            rs.getString("state"),
            rs.getString("created_by"),
            readInstant(rs, "created_at"),
            readInstant(rs, "effective_at"),
            readInstant(rs, "released_at"),
            rs.getString("released_by"),
            rs.getString("release_reason"));

    private static final RowMapper<LockPointRow> LOCK_POINT_MAPPER = (rs, rowNum) -> new LockPointRow(
            rs.getInt("seq_no"),
            rs.getString("point_id"),
            readInstant(rs, "released_at"),
            rs.getString("released_by"),
            rs.getString("release_reason"));

    private static final RowMapper<LockEvent> EVENT_MAPPER = (rs, rowNum) -> new LockEvent(
            rs.getLong("id"),
            rs.getString("order_id"),
            rs.getInt("seq_no"),
            rs.getString("point_id"),
            rs.getString("event_type"),
            rs.getString("role"),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getString("reason"),
            readInstant(rs, "created_at"));

    private static final RowMapper<RejectedReading> REJECTED_MAPPER = (rs, rowNum) -> new RejectedReading(
            rs.getLong("id"),
            rs.getString("equipment_id"),
            rs.getString("order_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getString("requested_by"),
            rs.getString("request_id"),
            readInstant(rs, "rejected_at"));

    // ---------- 隔离令 ----------

    public void insertOrder(IsolationOrder order) {
        jdbc.update("INSERT INTO isolation_order (order_id, equipment_id, state, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                order.orderId(), order.equipmentId(), order.state(), order.createdBy(),
                utc(order.createdAt()));
    }

    public Optional<IsolationOrder> findOrder(String orderId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, state, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM isolation_order WHERE order_id = ?",
                ORDER_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一隔离令的确认/解除串行化。 */
    public Optional<IsolationOrder> findOrderForUpdate(String orderId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, state, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM isolation_order WHERE order_id = ? FOR UPDATE",
                ORDER_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    /** 设备当前未结束（PENDING/ISOLATED）的隔离令；同设备同时至多一条。 */
    public Optional<IsolationOrder> findOpenOrderForEquipment(String equipmentId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, state, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM isolation_order WHERE equipment_id = ? AND state IN ('PENDING','ISOLATED')"
                        + " ORDER BY created_at ASC, order_id ASC LIMIT 1",
                ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 设备当前生效中的隔离令（ISOLATED），用于读数写入门禁。 */
    public Optional<IsolationOrder> findIsolatedOrderForEquipment(String equipmentId) {
        List<IsolationOrder> rows = jdbc.query(
                "SELECT order_id, equipment_id, state, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM isolation_order WHERE equipment_id = ? AND state = 'ISOLATED'"
                        + " ORDER BY created_at ASC, order_id ASC LIMIT 1",
                ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 隔离令生效：PENDING → ISOLATED，固化生效时刻。 */
    public void markEffective(String orderId, Instant effectiveAt) {
        jdbc.update("UPDATE isolation_order SET state = 'ISOLATED', effective_at = ? WHERE order_id = ?",
                utc(effectiveAt), orderId);
    }

    /** 隔离令解除：ISOLATED → RELEASED，固化解除时刻、操作人与令级原因。 */
    public void markReleased(String orderId, Instant releasedAt, String releasedBy, String releaseReason) {
        jdbc.update("UPDATE isolation_order SET state = 'RELEASED', released_at = ?,"
                        + " released_by = ?, release_reason = ? WHERE order_id = ?",
                utc(releasedAt), releasedBy, releaseReason, orderId);
    }

    /** 设备全部隔离令（含历史），按创建时刻与标识升序，稳定排序。 */
    public List<IsolationOrder> listOrdersByEquipment(String equipmentId) {
        return jdbc.query(
                "SELECT order_id, equipment_id, state, created_by, created_at, effective_at,"
                        + " released_at, released_by, release_reason"
                        + " FROM isolation_order WHERE equipment_id = ?"
                        + " ORDER BY created_at ASC, order_id ASC",
                ORDER_MAPPER, equipmentId);
    }

    // ---------- 锁定点 ----------

    public void insertLockPoint(String orderId, int seqNo, String pointId) {
        jdbc.update("INSERT INTO lock_point (order_id, point_id, seq_no) VALUES (?, ?, ?)",
                orderId, pointId, seqNo);
    }

    /** 锁定点行（含解除列），按规范化顺序升序。 */
    public List<LockPointRow> listLockPointRows(String orderId) {
        return jdbc.query(
                "SELECT seq_no, point_id, released_at, released_by, release_reason"
                        + " FROM lock_point WHERE order_id = ? ORDER BY seq_no ASC",
                LOCK_POINT_MAPPER, orderId);
    }

    /** 逐点解除：固化解除时刻、操作人与原因。 */
    public void releaseLockPoint(String orderId, String pointId, Instant releasedAt,
                                 String releasedBy, String reason) {
        jdbc.update("UPDATE lock_point SET released_at = ?, released_by = ?, release_reason = ?"
                        + " WHERE order_id = ? AND point_id = ?",
                utc(releasedAt), releasedBy, reason, orderId, pointId);
    }

    public record LockPointRow(int seqNo, String pointId, Instant releasedAt,
                               String releasedBy, String releaseReason) {
    }

    // ---------- 事件流水 ----------

    public void insertLockEvent(String orderId, int seqNo, String pointId, String eventType,
                                String role, String operator, String requestId,
                                String reason, Instant createdAt) {
        jdbc.update("INSERT INTO lock_event (order_id, seq_no, point_id, event_type, role, operator,"
                        + " request_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, seqNo, pointId, eventType, role, operator, requestId, reason, utc(createdAt));
    }

    /** 事件流水按自增 id 升序（即发生顺序），稳定排序。 */
    public List<LockEvent> listLockEvents(String orderId) {
        return jdbc.query(
                "SELECT id, order_id, seq_no, point_id, event_type, role, operator, request_id,"
                        + " reason, created_at FROM lock_event WHERE order_id = ? ORDER BY id ASC",
                EVENT_MAPPER, orderId);
    }

    // ---------- 被拒读数证据 ----------

    public long insertRejectedReading(RejectedReading rejected) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rejected_reading (equipment_id, order_id, reading_id, sampled_at,"
                            + " cumulative_minutes, requested_by, request_id, rejected_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rejected.equipmentId());
            ps.setString(2, rejected.orderId());
            ps.setString(3, rejected.readingId());
            ps.setObject(4, utc(rejected.sampledAt()));
            ps.setLong(5, rejected.cumulativeMinutes());
            ps.setString(6, rejected.requestedBy());
            ps.setString(7, rejected.requestId());
            ps.setObject(8, utc(rejected.rejectedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("被拒读数证据主键生成失败");
        }
        return key.longValue();
    }

    /** 设备被拒读数证据，按拒绝时刻与自增 id 升序，稳定排序。 */
    public List<RejectedReading> listRejectedReadings(String equipmentId) {
        return jdbc.query(
                "SELECT id, equipment_id, order_id, reading_id, sampled_at, cumulative_minutes,"
                        + " requested_by, request_id, rejected_at"
                        + " FROM rejected_reading WHERE equipment_id = ? ORDER BY rejected_at ASC, id ASC",
                REJECTED_MAPPER, equipmentId);
    }
}
