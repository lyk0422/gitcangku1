package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TaskCancelReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务取消原因数据访问。任务取消时在同一事务写入，唯一约束 uk_cancel_reason_task
 * 保证每任务至多一条；记录只增不改。
 */
@Repository
public class TaskCancelReasonRepository {

    private static final RowMapper<TaskCancelReason> MAPPER = (rs, rowNum) -> new TaskCancelReason(
            rs.getLong("id"), rs.getLong("task_id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getString("reason_code"), rs.getString("detail"), rs.getString("operator"),
            rs.getTimestamp("created_at").toLocalDateTime().toString());

    private static final String COLUMNS = "id, task_id, release_id, device_id, reason_code, detail, operator,"
            + " created_at";

    private final JdbcTemplate jdbc;

    public TaskCancelReasonRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, long releaseId, String deviceId, String reasonCode,
                       String detail, String operator) {
        jdbc.update("INSERT INTO task_cancel_reason"
                        + " (task_id, release_id, device_id, reason_code, detail, operator)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                taskId, releaseId, deviceId, reasonCode, detail, operator);
    }

    public Optional<TaskCancelReason> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_cancel_reason WHERE task_id = ?",
                MAPPER, taskId).stream().findFirst();
    }

    public List<TaskCancelReason> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_cancel_reason WHERE device_id = ? ORDER BY id",
                MAPPER, deviceId);
    }
}
