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

import com.example.starter.maintenance.domain.IsolationConfirmation;
import com.example.starter.maintenance.domain.IsolationLockPoint;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 能源隔离数据访问层。所有 SQL 参数化；时刻字段以 UTC 存取。
 * 隔离令、锁定点、确认、解除、被拒读数证据均只追加，不提供更新业务证据的方法
 * （隔离令仅允许状态机迁移：激活、解除）。
 */
@Repository
public class IsolationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IsolationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getString("request_id"),
            readInstant(rs, "created_at"),
            readInstant(rs, "activated_at"),
            rs.getString("activated_by"),
            rs.getString("activated_request_id"),
            readInstant(rs, "released_at"),
            rs.getString("released_by"),
            rs.getString("release_reason"),
            rs.getString("release_request_id"));

    private static final String ORDER_COLUMNS =
            "order_id, equipment_id, status, created_by, request_id, created_at, activated_at,"
                    + " activated_by, activated_request_id, released_at, released_by, release_reason,"
                    + " release_request_id";

    private static final RowMapper<IsolationLockPoint> LOCK_POINT_MAPPER = (rs, rowNum) ->
            new IsolationLockPoint(
                    rs.getString("order_id"),
                    rs.getString("lock_point_id"),
                    rs.getInt("ordinal"),
                    rs.getString("lock_point_name"));

    private static final RowMapper<IsolationConfirmation> CONFIRMATION_MAPPER = (rs, rowNum) ->
            new IsolationConfirmation(
                    rs.getLong("confirmation_id"),
                    rs.getString("order_id"),
                    rs.getString("lock_point_id"),
                    rs.getString("operator_id"),
                    rs.getString("role_id"),
                    rs.getString("request_id"),
                    readInstant(rs, "confirmed_at"));

    // ---------- 隔离令 ----------

    public void insertOrder(IsolationOrder order) {
        jdbc.update("INSERT INTO isolation_order (order_id, equipment_id, status, created_by, request_id,"
                        + " created_at, activated_at, activated_by, activated_request_id, released_at,"
                        + " released_by, release_reason, release_request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.orderId(), order.equipmentId(), order.status(), order.createdBy(), order.requestId(),
                utc(order.createdAt()), utc(order.activatedAt()), order.activatedBy(),
                order.activatedRequestId(), utc(order.releasedAt()), order.releasedBy(),
                order.releaseReason(), order.releaseRequestId());
    }

    public Optional<IsolationOrder> findOrder(String orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM isolation_order WHERE order_id = ?",
                ORDER_MAPPER, orderId).stream().findFirst();
    }

    /** 按主键加行锁读取隔离令。 */
    public Optional<IsolationOrder> findOrderForUpdate(String orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM isolation_order WHERE order_id = ? FOR UPDATE",
                ORDER_MAPPER, orderId).stream().findFirst();
    }

    /** 设备当前生效（ACTIVE）的隔离令；无则空。 */
    public Optional<IsolationOrder> findActiveOrder(String equipmentId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS
                        + " FROM isolation_order WHERE equipment_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY created_at DESC, order_id DESC LIMIT 1",
                ORDER_MAPPER, equipmentId).stream().findFirst();
    }

    public boolean existsOpenOrder(String equipmentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_order WHERE equipment_id = ?"
                        + " AND status IN ('PENDING','ACTIVE')",
                Integer.class, equipmentId);
        return count != null && count > 0;
    }

    /** PENDING → ACTIVE 状态迁移。 */
    public void activateOrder(String orderId, Instant activatedAt, String activatedBy, String requestId) {
        jdbc.update("UPDATE isolation_order SET status = 'ACTIVE', activated_at = ?, activated_by = ?,"
                        + " activated_request_id = ? WHERE order_id = ? AND status = 'PENDING'",
                utc(activatedAt), activatedBy, requestId, orderId);
    }

    /** ACTIVE → RELEASED 状态迁移。 */
    public void releaseOrder(String orderId, Instant releasedAt, String releasedBy, String reason,
                             String requestId) {
        jdbc.update("UPDATE isolation_order SET status = 'RELEASED', released_at = ?, released_by = ?,"
                        + " release_reason = ?, release_request_id = ? WHERE order_id = ? AND status = 'ACTIVE'",
                utc(releasedAt), releasedBy, reason, requestId, orderId);
    }

    public List<IsolationOrder> listOrders(String equipmentId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS
                        + " FROM isolation_order WHERE equipment_id = ?"
                        + " ORDER BY created_at ASC, order_id ASC",
                ORDER_MAPPER, equipmentId);
    }

    // ---------- 锁定点 ----------

    public void insertLockPoint(String orderId, String lockPointId, int ordinal, String name) {
        jdbc.update("INSERT INTO isolation_lock_point (order_id, lock_point_id, ordinal, lock_point_name)"
                        + " VALUES (?, ?, ?, ?)",
                orderId, lockPointId, ordinal, name);
    }

    public List<IsolationLockPoint> listLockPoints(String orderId) {
        return jdbc.query(
                "SELECT order_id, lock_point_id, ordinal, lock_point_name FROM isolation_lock_point"
                        + " WHERE order_id = ? ORDER BY ordinal ASC, lock_point_id ASC",
                LOCK_POINT_MAPPER, orderId);
    }

    // ---------- 确认 ----------

    public void insertConfirmation(String orderId, String lockPointId, String operatorId, String roleId,
                                   String requestId, Instant confirmedAt) {
        jdbc.update("INSERT INTO isolation_confirmation (order_id, lock_point_id, operator_id, role_id,"
                        + " request_id, confirmed_at) VALUES (?, ?, ?, ?, ?, ?)",
                orderId, lockPointId, operatorId, roleId, requestId, utc(confirmedAt));
    }

    public List<IsolationConfirmation> listConfirmations(String orderId) {
        return jdbc.query(
                "SELECT confirmation_id, order_id, lock_point_id, operator_id, role_id, request_id,"
                        + " confirmed_at FROM isolation_confirmation WHERE order_id = ?"
                        + " ORDER BY confirmed_at ASC, confirmation_id ASC",
                CONFIRMATION_MAPPER, orderId);
    }

    // ---------- 解除 ----------

    public void insertLockRelease(String orderId, String lockPointId, String operatorId,
                                  String requestId, Instant releasedAt) {
        jdbc.update("INSERT INTO isolation_lock_release (order_id, lock_point_id, operator_id, request_id,"
                        + " released_at) VALUES (?, ?, ?, ?, ?)",
                orderId, lockPointId, operatorId, requestId, utc(releasedAt));
    }

    /** 返回已解除锁定点标识集合（用于诊断/校验）。 */
    public List<String> listReleasedLockPointIds(String orderId) {
        return jdbc.queryForList(
                "SELECT lock_point_id FROM isolation_lock_release WHERE order_id = ? ORDER BY lock_point_id ASC",
                String.class, orderId);
    }

    // ---------- 被拒读数证据 ----------

    public long insertRejection(String equipmentId, String orderId, String readingId, String operatorId,
                                String requestId, Instant sampledAt, long cumulativeMinutes,
                                List<String> lockPointSnapshot, Instant rejectedAt) {
        String snapshotJson = writeSnapshot(lockPointSnapshot);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO isolated_reading_rejection (equipment_id, order_id, reading_id,"
                            + " operator_id, request_id, sampled_at, cumulative_minutes, lock_point_count,"
                            + " lock_point_snapshot, rejected_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, orderId);
            ps.setString(3, readingId);
            ps.setString(4, operatorId);
            ps.setString(5, requestId);
            ps.setObject(6, utc(sampledAt));
            ps.setLong(7, cumulativeMinutes);
            ps.setInt(8, lockPointSnapshot.size());
            ps.setString(9, snapshotJson);
            ps.setObject(10, utc(rejectedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("被拒读数证据主键生成失败");
        }
        return key.longValue();
    }

    public List<RejectionRow> listRejections(String equipmentId) {
        return jdbc.query(
                "SELECT rejection_id, equipment_id, order_id, reading_id, operator_id, request_id,"
                        + " sampled_at, cumulative_minutes, lock_point_count, lock_point_snapshot, rejected_at"
                        + " FROM isolated_reading_rejection WHERE equipment_id = ?"
                        + " ORDER BY rejected_at ASC, rejection_id ASC",
                (rs, rowNum) -> new RejectionRow(
                        rs.getLong("rejection_id"),
                        rs.getString("equipment_id"),
                        rs.getString("order_id"),
                        rs.getString("reading_id"),
                        rs.getString("operator_id"),
                        rs.getString("request_id"),
                        readInstant(rs, "sampled_at"),
                        rs.getLong("cumulative_minutes"),
                        rs.getInt("lock_point_count"),
                        readSnapshot(rs.getString("lock_point_snapshot")),
                        readInstant(rs, "rejected_at")),
                equipmentId);
    }

    private String writeSnapshot(List<String> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("锁定点快照序列化失败", e);
        }
    }

    private List<String> readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("锁定点快照反序列化失败", e);
        }
    }

    public record RejectionRow(
            long rejectionId,
            String equipmentId,
            String orderId,
            String readingId,
            String operatorId,
            String requestId,
            Instant sampledAt,
            long cumulativeMinutes,
            int lockPointCount,
            List<String> lockPointSnapshot,
            Instant rejectedAt) {
    }
}
