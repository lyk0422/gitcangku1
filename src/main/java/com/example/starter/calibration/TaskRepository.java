package com.example.starter.calibration;

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
 * 接收任务访问。
 */
@Repository
public class TaskRepository {

    private static final String COLUMNS = "id, task_code, antenna_id, start_time, end_time, status";

    private static final RowMapper<ReceiveTask> MAPPER = (rs, n) -> new ReceiveTask(
            rs.getLong("id"),
            rs.getString("task_code"),
            rs.getLong("antenna_id"),
            JdbcTimes.toInstant(rs, "start_time"),
            JdbcTimes.toInstant(rs, "end_time"),
            TaskStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入任务并返回主键；createdAt 为记录创建时刻（UTC）。 */
    public long insert(ReceiveTask task, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO receive_task (task_code, antenna_id, start_time, end_time, status, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.taskCode());
            ps.setLong(2, task.antennaId());
            ps.setTimestamp(3, JdbcTimes.toParam(task.startTime()));
            ps.setTimestamp(4, JdbcTimes.toParam(task.endTime()));
            ps.setString(5, task.status().name());
            ps.setTimestamp(6, JdbcTimes.toParam(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<ReceiveTask> findById(long id) {
        List<ReceiveTask> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM receive_task WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 标记任务已完成。 */
    public void markCompleted(long id) {
        jdbc.update("UPDATE receive_task SET status = ? WHERE id = ?",
                TaskStatus.COMPLETED.name(), id);
    }
}