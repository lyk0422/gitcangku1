package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 执行时段安排的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行、再持有 duty_person_lock 人员锁的写事务内，
 * 使同人员的休息与日上限校验相对其他安排变更串行化（含跨事件并发）。
 */
@Repository
public class TaskScheduleRepository {

    private final JdbcTemplate jdbc;

    public TaskScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskSchedule> MAPPER = (rs, n) -> map(rs);

    private static TaskSchedule map(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        return new TaskSchedule(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("assignee"),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                TaskScheduleStatus.valueOf(rs.getString("status")),
                startedAt == null ? null : startedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入新安排（SCHEDULED），返回生成主键。uk_schedule_task 唯一约束兜底并发重复插入。
     */
    public long insert(TaskSchedule schedule) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_task_schedules (task_id, assignee, start_at, end_at, status,"
                            + " started_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, schedule.taskId());
            ps.setString(2, schedule.assignee());
            ps.setTimestamp(3, Timestamp.from(schedule.startAt()));
            ps.setTimestamp(4, Timestamp.from(schedule.endAt()));
            ps.setString(5, schedule.status().name());
            ps.setTimestamp(6, schedule.startedAt() == null ? null : Timestamp.from(schedule.startedAt()));
            ps.setTimestamp(7, Timestamp.from(schedule.createdAt()));
            ps.setTimestamp(8, Timestamp.from(schedule.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按任务查询安排（每任务至多一条），用于明细组装与状态判定。
     */
    public Optional<TaskSchedule> findByTask(long taskId) {
        List<TaskSchedule> rows = jdbc.query(
                "SELECT * FROM incident_task_schedules WHERE task_id = ?", MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询人员全部计入约束的安排（SCHEDULED/STARTED），跨事件核对休息与日上限用。
     */
    public List<TaskSchedule> listActiveByAssignee(String assignee) {
        return jdbc.query("SELECT * FROM incident_task_schedules WHERE assignee = ?"
                        + " AND status IN ('SCHEDULED','STARTED') ORDER BY start_at",
                MAPPER, assignee);
    }

    /**
     * 整行替换安排时段与人员（仅未开始或已撤销安排允许），状态重置为 SCHEDULED。
     */
    public void replace(long id, String assignee, Instant startAt, Instant endAt, Instant updatedAt) {
        jdbc.update("UPDATE incident_task_schedules SET assignee = ?, start_at = ?, end_at = ?,"
                        + " status = 'SCHEDULED', started_at = NULL, updated_at = ? WHERE id = ?",
                assignee, Timestamp.from(startAt), Timestamp.from(endAt),
                Timestamp.from(updatedAt), id);
    }

    /**
     * 将 SCHEDULED 安排置为 STARTED，记录开始 UTC 时刻。
     */
    public void markStarted(long id, Instant startedAt) {
        jdbc.update("UPDATE incident_task_schedules SET status = 'STARTED', started_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                Timestamp.from(startedAt), Timestamp.from(startedAt), id);
    }

    /**
     * 将 SCHEDULED 安排置为 REVOKED（撤销或任务取消时释放约束）。
     */
    public void markRevoked(long id, Instant updatedAt) {
        jdbc.update("UPDATE incident_task_schedules SET status = 'REVOKED', updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(updatedAt), id);
    }

    /**
     * 持有人员级锁（单行 SELECT ... FOR UPDATE），串行化同人员的安排校验与写入。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockPerson(String assignee) {
        try {
            jdbc.update("INSERT INTO duty_person_lock (assignee) VALUES (?)", assignee);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT assignee FROM duty_person_lock WHERE assignee = ? FOR UPDATE",
                String.class, assignee);
    }
}
