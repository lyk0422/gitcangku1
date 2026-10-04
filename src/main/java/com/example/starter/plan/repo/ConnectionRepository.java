package com.example.starter.plan.repo;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionEvent;
import com.example.starter.plan.model.ConnectionEventType;
import com.example.starter.plan.model.ConnectionStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接续关系与接续历史事件的 JDBC 持久化。事件只追加不改写；所有时刻为 UTC 毫秒。
 */
@Repository
public class ConnectionRepository {

    private static final RowMapper<Connection> CONNECTION_MAPPER = (rs, n) -> new Connection(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("guarantee_minutes"),
            rs.getBoolean("crosses_midnight"),
            ConnectionStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getObject("cancelled_at", Long.class));

    private static final RowMapper<ConnectionEvent> EVENT_MAPPER = (rs, n) -> new ConnectionEvent(
            rs.getLong("id"),
            rs.getLong("connection_id"),
            ConnectionEventType.valueOf(rs.getString("event_type")),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("predecessor_version"),
            toInstant(rs.getObject("old_time_utc", Long.class)),
            toInstant(rs.getObject("new_time_utc", Long.class)),
            rs.getObject("required_minutes", Integer.class),
            rs.getObject("actual_minutes", Integer.class),
            rs.getObject("shortfall_minutes", Integer.class),
            toInstant(rs.getObject("window_start_utc", Long.class)),
            toInstant(rs.getObject("window_end_utc", Long.class)),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    /**
     * 登记接续（状态 ACTIVE），返回自增主键。
     */
    public long insert(long predecessorPlanId, long successorPlanId, int guaranteeMinutes,
                       boolean crossesMidnight, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection (predecessor_plan_id, successor_plan_id,"
                            + " guarantee_minutes, crosses_midnight, status, created_at, cancelled_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, predecessorPlanId);
            ps.setLong(2, successorPlanId);
            ps.setInt(3, guaranteeMinutes);
            ps.setBoolean(4, crossesMidnight);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询接续（不加锁）。
     */
    public Optional<Connection> findById(long id) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                        + " crosses_midnight, status, created_at, cancelled_at"
                        + " FROM rail_connection WHERE id = ?",
                CONNECTION_MAPPER, id).stream().findFirst();
    }

    /**
     * 按主键查询并加行级写锁，须在事务内调用。
     */
    public Optional<Connection> findByIdForUpdate(long id) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                        + " crosses_midnight, status, created_at, cancelled_at"
                        + " FROM rail_connection WHERE id = ? FOR UPDATE",
                CONNECTION_MAPPER, id).stream().findFirst();
    }

    /**
     * 查询指定前后车之间生效中的接续。
     */
    public Optional<Connection> findActive(long predecessorPlanId, long successorPlanId) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                        + " crosses_midnight, status, created_at, cancelled_at"
                        + " FROM rail_connection WHERE predecessor_plan_id = ?"
                        + " AND successor_plan_id = ? AND status = 'ACTIVE'",
                CONNECTION_MAPPER, predecessorPlanId, successorPlanId).stream().findFirst();
    }

    /**
     * 查询以指定计划为前车的全部生效接续，按 id 升序。
     */
    public List<Connection> findActiveByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                        + " crosses_midnight, status, created_at, cancelled_at"
                        + " FROM rail_connection WHERE predecessor_plan_id = ?"
                        + " AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, predecessorPlanId);
    }

    /**
     * 查询以指定计划为后车的全部生效接续，按 id 升序。
     */
    public List<Connection> findActiveBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                        + " crosses_midnight, status, created_at, cancelled_at"
                        + " FROM rail_connection WHERE successor_plan_id = ?"
                        + " AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, successorPlanId);
    }

    /**
     * 查询与指定计划相关（任一侧）的全部接续（含已取消），按 id 升序。
     */
    public List<Connection> findByPlan(long planId) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                        + " crosses_midnight, status, created_at, cancelled_at"
                        + " FROM rail_connection WHERE predecessor_plan_id = ?"
                        + " OR successor_plan_id = ? ORDER BY id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 前车改签后接续跟随到新计划。
     */
    public void updatePredecessor(long connectionId, long newPredecessorPlanId) {
        jdbc.update("UPDATE rail_connection SET predecessor_plan_id = ? WHERE id = ?",
                newPredecessorPlanId, connectionId);
    }

    /**
     * 后车改签后接续跟随到新计划。
     */
    public void updateSuccessor(long connectionId, long newSuccessorPlanId) {
        jdbc.update("UPDATE rail_connection SET successor_plan_id = ? WHERE id = ?",
                newSuccessorPlanId, connectionId);
    }

    /**
     * 取消接续：仅更新当前状态与取消时刻，历史事件不改写。
     */
    public void cancel(long connectionId, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                nowMillis, connectionId);
    }
    /**
     * 追加接续历史事件（只追加不改写），返回自增主键。
     */
    public long insertEvent(ConnectionEvent event) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection_event (connection_id, event_type,"
                            + " predecessor_plan_id, successor_plan_id, predecessor_version,"
                            + " old_time_utc, new_time_utc, required_minutes, actual_minutes,"
                            + " shortfall_minutes, window_start_utc, window_end_utc, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, event.connectionId());
            ps.setString(2, event.eventType().name());
            ps.setLong(3, event.predecessorPlanId());
            ps.setLong(4, event.successorPlanId());
            ps.setInt(5, event.predecessorVersion());
            setInstant(ps, 6, event.oldTimeUtc());
            setInstant(ps, 7, event.newTimeUtc());
            setInteger(ps, 8, event.requiredMinutes());
            setInteger(ps, 9, event.actualMinutes());
            setInteger(ps, 10, event.shortfallMinutes());
            setInstant(ps, 11, event.windowStartUtc());
            setInstant(ps, 12, event.windowEndUtc());
            ps.setLong(13, event.createdAt());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value.toEpochMilli());
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * 查询接续的全部历史事件，按 id 升序（即发生顺序）。
     */
    public List<ConnectionEvent> findEvents(long connectionId) {
        return jdbc.query("SELECT id, connection_id, event_type, predecessor_plan_id,"
                        + " successor_plan_id, predecessor_version, old_time_utc, new_time_utc,"
                        + " required_minutes, actual_minutes, shortfall_minutes,"
                        + " window_start_utc, window_end_utc, created_at"
                        + " FROM rail_connection_event WHERE connection_id = ? ORDER BY id",
                EVENT_MAPPER, connectionId);
    }

    /**
     * 查询接续最近一条 RISK 事件（用于诊断查询展示当前建议时间窗）。
     */
    public Optional<ConnectionEvent> findLatestRiskEvent(long connectionId) {
        return jdbc.query("SELECT id, connection_id, event_type, predecessor_plan_id,"
                        + " successor_plan_id, predecessor_version, old_time_utc, new_time_utc,"
                        + " required_minutes, actual_minutes, shortfall_minutes,"
                        + " window_start_utc, window_end_utc, created_at"
                        + " FROM rail_connection_event WHERE connection_id = ?"
                        + " AND event_type = 'RISK' ORDER BY id DESC LIMIT 1",
                EVENT_MAPPER, connectionId).stream().findFirst();
    }
}