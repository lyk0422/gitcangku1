package com.example.starter.plan.repo;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionEvent;
import com.example.starter.plan.model.ConnectionStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接续关系与接续历史事件的 JDBC 持久化。历史事件只追加不改写；
 * 所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class ConnectionRepository {

    private static final String CONNECTION_COLUMNS = "id, predecessor_plan_id, successor_plan_id,"
            + " guarantee_minutes, from_station, to_station, crosses_midnight, status,"
            + " created_at, updated_at";

    private static final RowMapper<Connection> CONNECTION_MAPPER = (rs, n) -> new Connection(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("guarantee_minutes"),
            rs.getString("from_station"),
            rs.getString("to_station"),
            rs.getBoolean("crosses_midnight"),
            ConnectionStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<ConnectionEvent> EVENT_MAPPER = (rs, n) -> new ConnectionEvent(
            rs.getLong("id"),
            rs.getLong("connection_id"),
            rs.getString("event_type"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("predecessor_version"),
            rs.getObject("old_time_utc", Long.class),
            rs.getObject("new_time_utc", Long.class),
            rs.getObject("required_minutes", Integer.class),
            rs.getObject("actual_minutes", Integer.class),
            rs.getObject("deficit_minutes", Integer.class),
            rs.getObject("window_start_utc", Long.class),
            rs.getObject("window_end_utc", Long.class),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新接续关系（ACTIVE），返回自增主键。
     */
    public long insert(Connection connection) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection (predecessor_plan_id, successor_plan_id,"
                            + " guarantee_minutes, from_station, to_station, crosses_midnight,"
                            + " status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, connection.predecessorPlanId());
            ps.setLong(2, connection.successorPlanId());
            ps.setInt(3, connection.guaranteeMinutes());
            ps.setString(4, connection.fromStation());
            ps.setString(5, connection.toStation());
            ps.setBoolean(6, connection.crossesMidnight());
            ps.setString(7, connection.status().name());
            ps.setLong(8, connection.createdAt());
            ps.setLong(9, connection.updatedAt());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 全量更新接续参数与状态（重新登记复活、取消、改签后重指向共用）。
     */
    public void update(Connection connection) {
        jdbc.update("UPDATE rail_connection SET predecessor_plan_id = ?, successor_plan_id = ?,"
                        + " guarantee_minutes = ?, from_station = ?, to_station = ?,"
                        + " crosses_midnight = ?, status = ?, updated_at = ? WHERE id = ?",
                connection.predecessorPlanId(), connection.successorPlanId(),
                connection.guaranteeMinutes(), connection.fromStation(), connection.toStation(),
                connection.crossesMidnight(), connection.status().name(),
                connection.updatedAt(), connection.id());
    }

    /**
     * 按主键查询接续（不加锁）。
     */
    public Optional<Connection> findById(long id) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection WHERE id = ?",
                CONNECTION_MAPPER, id).stream().findFirst();
    }

    /**
     * 按主键查询接续并加行级写锁，须在事务内调用。
     */
    public Optional<Connection> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection WHERE id = ? FOR UPDATE",
                CONNECTION_MAPPER, id).stream().findFirst();
    }

    /**
     * 按前后车计划对查询接续（含已取消），同一对全局一行。
     */
    public Optional<Connection> findByPair(long predecessorPlanId, long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND successor_plan_id = ?",
                CONNECTION_MAPPER, predecessorPlanId, successorPlanId).stream().findFirst();
    }

    /**
     * 查询指定计划作为前车的全部生效接续，按 id 升序（登记顺序）。
     */
    public List<Connection> findActiveByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, predecessorPlanId);
    }

    /**
     * 查询指定计划作为后车的全部生效接续，按 id 升序（登记顺序）。
     */
    public List<Connection> findActiveBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE successor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, successorPlanId);
    }

    /**
     * 查询指定计划参与（任一端）的全部接续（含已取消），按 id 升序。
     */
    public List<Connection> findByPlan(long planId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? OR successor_plan_id = ? ORDER BY id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 追加接续历史事件（只追加不改写）。
     */
    public void insertEvent(ConnectionEvent event) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection_event (connection_id, event_type,"
                            + " predecessor_plan_id, successor_plan_id, predecessor_version,"
                            + " old_time_utc, new_time_utc, required_minutes, actual_minutes,"
                            + " deficit_minutes, window_start_utc, window_end_utc, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setLong(1, event.connectionId());
            ps.setString(2, event.eventType());
            ps.setLong(3, event.predecessorPlanId());
            ps.setLong(4, event.successorPlanId());
            ps.setInt(5, event.predecessorVersion());
            setNullableLong(ps, 6, event.oldTimeUtc());
            setNullableLong(ps, 7, event.newTimeUtc());
            setNullableInt(ps, 8, event.requiredMinutes());
            setNullableInt(ps, 9, event.actualMinutes());
            setNullableInt(ps, 10, event.deficitMinutes());
            setNullableLong(ps, 11, event.windowStartUtc());
            setNullableLong(ps, 12, event.windowEndUtc());
            ps.setLong(13, event.createdAt());
            return ps;
        });
    }

    /**
     * 查询指定接续的全部历史事件，按 id 升序（发生顺序）。
     */
    public List<ConnectionEvent> findEvents(long connectionId) {
        return jdbc.query("SELECT id, connection_id, event_type, predecessor_plan_id,"
                        + " successor_plan_id, predecessor_version, old_time_utc, new_time_utc,"
                        + " required_minutes, actual_minutes, deficit_minutes,"
                        + " window_start_utc, window_end_utc, created_at"
                        + " FROM rail_connection_event WHERE connection_id = ? ORDER BY id",
                EVENT_MAPPER, connectionId);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}