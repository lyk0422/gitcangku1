package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 资源租约证据与任务执行日志的 JDBC 仓储。
 * 两张表均只追加、不改写：后续任务修订或事件合并不改写原资源使用证据，
 * 字段、排序（id 升序）、时间精度（微秒）与 null 含义保持稳定。
 */
@Repository
public class ResourceEvidenceRepository {

    private final JdbcTemplate jdbc;

    public ResourceEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceEvent> EVENT_MAPPER = (rs, n) -> mapEvent(rs);
    private static final RowMapper<TaskExecutionLog> LOG_MAPPER = (rs, n) -> new TaskExecutionLog(
            rs.getLong("id"), rs.getLong("task_id"), rs.getString("entry_type"),
            rs.getString("note"), rs.getString("actor"),
            rs.getTimestamp("occurred_at").toInstant());

    private static ResourceEvent mapEvent(ResultSet rs) throws SQLException {
        return new ResourceEvent(
                rs.getLong("id"), rs.getLong("assignment_id"), rs.getLong("incident_id"),
                rs.getLong("task_id"), rs.getString("resource_key"),
                ResourceEventType.valueOf(rs.getString("event_type")),
                rs.getObject("old_version", Integer.class),
                rs.getObject("new_version", Integer.class),
                rs.getString("actor"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("detail"));
    }

    /**
     * 追加一条租约证据（ASSIGN/RENEW/EXPIRE/EXECUTE_DENIED/RELEASE）。
     */
    public void insertEvent(ResourceEvent event) {
        jdbc.update("INSERT INTO resource_assignment_events (assignment_id, incident_id, task_id,"
                        + " resource_key, event_type, old_version, new_version, actor, occurred_at,"
                        + " detail) VALUES (?,?,?,?,?,?,?,?,?,?)",
                event.assignmentId(), event.incidentId(), event.taskId(), event.resourceKey(),
                event.eventType().name(), event.oldVersion(), event.newVersion(), event.actor(),
                Timestamp.from(event.occurredAt()), event.detail());
    }

    /**
     * 查询任务的全部租约证据，按 id 升序（发生顺序）返回。
     */
    public List<ResourceEvent> listEventsByTask(long taskId) {
        return jdbc.query("SELECT * FROM resource_assignment_events WHERE task_id = ? ORDER BY id",
                EVENT_MAPPER, taskId);
    }

    /**
     * 追加一条任务执行日志（START/PROGRESS）；授权到期后日志保留。
     */
    public void insertExecutionLog(TaskExecutionLog log) {
        jdbc.update("INSERT INTO task_execution_logs (task_id, entry_type, note, actor,"
                        + " occurred_at) VALUES (?,?,?,?,?)",
                log.taskId(), log.entryType(), log.note(), log.actor(),
                Timestamp.from(log.occurredAt()));
    }

    /**
     * 查询任务的全部执行日志，按 id 升序返回。
     */
    public List<TaskExecutionLog> listExecutionLogs(long taskId) {
        return jdbc.query("SELECT * FROM task_execution_logs WHERE task_id = ? ORDER BY id",
                LOG_MAPPER, taskId);
    }

    /**
     * 任务是否已开始执行（是否存在 START 日志）。
     */
    public boolean hasStartLog(long taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_execution_logs WHERE task_id = ? AND entry_type = 'START'",
                Integer.class, taskId);
        return count != null && count > 0;
    }
}
