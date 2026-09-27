package com.example.starter.plan.repo;

import com.example.starter.plan.model.ConnectionEvent;
import com.example.starter.plan.model.RailConnection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接续关系与接续事件的 JDBC 持久化。事件表只插不改，保证历史业务证据稳定。
 */
@Repository
public class ConnectionRepository {

    private static final String CONNECTION_COLUMNS =
            "id, predecessor_plan_id, successor_plan_id, guarantee_minutes, cross_midnight,"
                    + " status, created_at, cancelled_at";

    private static final RowMapper<RailConnection> CONNECTION_MAPPER = (rs, n) -> new RailConnection(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("guarantee_minutes"),
            rs.getBoolean("cross_midnight"),
            rs.getString("status"),
            rs.getLong("created_at"),
            rs.getObject("cancelled_at", Long.class));

    private static final String EVENT_COLUMNS =
            "id, event_type, connection_id, predecessor_plan_id, successor_plan_id,"
                    + " predecessor_version, pred_arrival_utc, succ_departure_utc,"
                    + " prev_succ_departure_utc, guarantee_minutes, actual_minutes, deficit_minutes,"
                    + " window_start_utc, window_end_utc, created_at";

    private static final RowMapper<ConnectionEvent> EVENT_MAPPER = (rs, n) -> new ConnectionEvent(
            rs.getLong("id"),
            rs.getString("event_type"),
            rs.getLong("connection_id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("predecessor_version"),
            rs.getLong("pred_arrival_utc"),
            rs.getLong("succ_departure_utc"),
            rs.getObject("prev_succ_departure_utc", Long.class),
            rs.getInt("guarantee_minutes"),
            rs.getLong("actual_minutes"),
            rs.getLong("deficit_minutes"),
            rs.getObject("window_start_utc", Long.class),
            rs.getObject("window_end_utc", Long.class),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记接续关系，返回自增主键；同对计划唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public long insertConnection(long predecessorPlanId, long successorPlanId, int guaranteeMinutes,
                                 boolean crossMidnight, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection"
                            + " (predecessor_plan_id, successor_plan_id, guarantee_minutes,"
                            + " cross_midnight, status, created_at, cancelled_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, predecessorPlanId);
            ps.setLong(2, successorPlanId);
            ps.setInt(3, guaranteeMinutes);
            ps.setBoolean(4, crossMidnight);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询接续（不加锁）。
     */
    public Optional<RailConnection> findById(long connectionId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection WHERE id = ?",
                CONNECTION_MAPPER, connectionId).stream().findFirst();
    }

    /**
     * 按主键查询接续并加行级写锁，须在事务内调用。
     */
    public Optional<RailConnection> findByIdForUpdate(long connectionId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection WHERE id = ? FOR UPDATE",
                CONNECTION_MAPPER, connectionId).stream().findFirst();
    }

    /**
     * 查询同对计划的接续（任意状态），用于重复登记裁决。
     */
    public Optional<RailConnection> findByPair(long predecessorPlanId, long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND successor_plan_id = ?",
                CONNECTION_MAPPER, predecessorPlanId, successorPlanId).stream().findFirst();
    }

    /**
     * 查询以指定计划为前车的生效接续，按 id 升序。
     */
    public List<RailConnection> findActiveByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, predecessorPlanId);
    }

    /**
     * 查询以指定计划为后车的生效接续，按 id 升序。
     */
    public List<RailConnection> findActiveBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE successor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, successorPlanId);
    }

    /**
     * 查询指定计划参与（前车或后车）的生效接续，按 id 升序。
     */
    public List<RailConnection> findActiveInvolving(long planId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE status = 'ACTIVE' AND (predecessor_plan_id = ? OR successor_plan_id = ?)"
                        + " ORDER BY id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 查询指定计划参与（前车或后车）的已取消接续，按 id 升序。
     */
    public List<RailConnection> findCancelledInvolving(long planId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE status = 'CANCELLED' AND (predecessor_plan_id = ? OR successor_plan_id = ?)"
                        + " ORDER BY id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 改签迁移：接续前车指向新计划。
     */
    public void updatePredecessor(long connectionId, long newPredecessorPlanId) {
        jdbc.update("UPDATE rail_connection SET predecessor_plan_id = ? WHERE id = ?",
                newPredecessorPlanId, connectionId);
    }

    /**
     * 改签迁移：接续后车指向新计划。
     */
    public void updateSuccessor(long connectionId, long newSuccessorPlanId) {
        jdbc.update("UPDATE rail_connection SET successor_plan_id = ? WHERE id = ?",
                newSuccessorPlanId, connectionId);
    }

    /**
     * 取消接续（状态置 CANCELLED 并记录取消时刻；行本身保留不改写其他字段）。
     */
    public void markCancelled(long connectionId, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                nowMillis, connectionId);
    }

    /**
     * 追加接续事件（只插不改）。
     */
    public void insertEvent(ConnectionEvent event) {
        jdbc.update("INSERT INTO rail_connection_event"
                        + " (event_type, connection_id, predecessor_plan_id, successor_plan_id,"
                        + " predecessor_version, pred_arrival_utc, succ_departure_utc,"
                        + " prev_succ_departure_utc, guarantee_minutes, actual_minutes, deficit_minutes,"
                        + " window_start_utc, window_end_utc, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.eventType(), event.connectionId(), event.predecessorPlanId(),
                event.successorPlanId(), event.predecessorVersion(), event.predArrivalUtc(),
                event.succDepartureUtc(), event.prevSuccDepartureUtc(), event.guaranteeMinutes(),
                event.actualMinutes(), event.deficitMinutes(), event.windowStartUtc(),
                event.windowEndUtc(), event.createdAt());
    }

    /**
     * 查询指定计划参与（前车或后车）的全部接续事件，按 id 升序（即发生顺序）。
     */
    public List<ConnectionEvent> findEventsByPlan(long planId) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM rail_connection_event"
                        + " WHERE predecessor_plan_id = ? OR successor_plan_id = ? ORDER BY id",
                EVENT_MAPPER, planId, planId);
    }

    /**
     * 查询指定接续的最新一条指定类型事件，用于风险判定去重与当前时间窗诊断。
     */
    public Optional<ConnectionEvent> findLatestEvent(long connectionId, String eventType) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM rail_connection_event"
                        + " WHERE connection_id = ? AND event_type = ? ORDER BY id DESC LIMIT 1",
                EVENT_MAPPER, connectionId, eventType).stream().findFirst();
    }
}
