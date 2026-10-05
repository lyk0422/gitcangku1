package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 资源授权证据与任务执行日志的 JDBC 仓储。
 * 两张表均为只增不改的追加式证据：不提供任何更新或删除方法，
 * 后续任务修订、事件合并不改写已固化记录；按 id 升序即发生顺序。
 */
@Repository
public class ResourceEvidenceRepository {

    private final JdbcTemplate jdbc;

    public ResourceEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceAuthorizationEvent> EVENT_MAPPER = (rs, n) -> mapEvent(rs);
    private static final RowMapper<TaskExecutionLog> LOG_MAPPER = (rs, n) -> new TaskExecutionLog(
            rs.getLong("id"), rs.getLong("task_id"), rs.getLong("incident_id"),
            rs.getString("action"), rs.getString("note"), rs.getString("actor"),
            rs.getTimestamp("occurred_at").toInstant());

    private static ResourceAuthorizationEvent mapEvent(ResultSet rs) throws SQLException {
        long oldVersion = rs.getLong("old_authorization_version");
        Long old = rs.wasNull() ? null : oldVersion;
        long newVersion = rs.getLong("new_authorization_version");
        Long newV = rs.wasNull() ? null : newVersion;
        return new ResourceAuthorizationEvent(
                rs.getLong("id"), ResourceEventType.valueOf(rs.getString("event_type")),
                rs.getLong("task_id"), rs.getLong("incident_id"), rs.getString("task_key"),
                rs.getString("resource_key"), old, newV, rs.getString("actor"),
                rs.getString("attempted_action"), rs.getTimestamp("occurred_at").toInstant());
    }

    /**
     * 追加一条资源授权证据，返回生成主键（即证据排序序号）。
     */
    public long insertEvent(ResourceAuthorizationEvent event) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_authorization_events (event_type, task_id, incident_id,"
                            + " task_key, resource_key, old_authorization_version,"
                            + " new_authorization_version, actor, attempted_action, occurred_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.eventType().name());
            ps.setLong(2, event.taskId());
            ps.setLong(3, event.incidentId());
            ps.setString(4, event.taskKey());
            ps.setString(5, event.resourceKey());
            if (event.oldAuthorizationVersion() == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, event.oldAuthorizationVersion());
            }
            if (event.newAuthorizationVersion() == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, event.newAuthorizationVersion());
            }
            ps.setString(8, event.actor());
            ps.setString(9, event.attemptedAction());
            ps.setTimestamp(10, Timestamp.from(event.occurredAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部资源授权证据，按发生顺序（id 升序）返回。
     */
    public List<ResourceAuthorizationEvent> listEventsByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM resource_authorization_events WHERE incident_id = ?"
                + " ORDER BY id", EVENT_MAPPER, incidentId);
    }

    /**
     * 查询任务全部资源授权证据，按发生顺序（id 升序）返回。
     */
    public List<ResourceAuthorizationEvent> listEventsByTask(long taskId) {
        return jdbc.query("SELECT * FROM resource_authorization_events WHERE task_id = ?"
                + " ORDER BY id", EVENT_MAPPER, taskId);
    }

    /**
     * 追加一条任务执行日志，返回生成主键。
     */
    public long insertExecutionLog(TaskExecutionLog log) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_execution_logs (task_id, incident_id, action, note, actor,"
                            + " occurred_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, log.taskId());
            ps.setLong(2, log.incidentId());
            ps.setString(3, log.action());
            ps.setString(4, log.note());
            ps.setString(5, log.actor());
            ps.setTimestamp(6, Timestamp.from(log.occurredAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务全部执行日志，按发生顺序（id 升序）返回。
     */
    public List<TaskExecutionLog> listExecutionLogsByTask(long taskId) {
        return jdbc.query("SELECT * FROM task_execution_logs WHERE task_id = ? ORDER BY id",
                LOG_MAPPER, taskId);
    }
}
