package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 取消原因写入后不可改：取消语句只更新仍处于非终态的行，终态行不再变化。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getString("cancel_reason"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result, cancel_reason";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status) VALUES (?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RolloutTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * 任务开始：仅当仍为 PENDING 时转为 IN_PROGRESS，返回影响行数。
     */
    public int startIfPending(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PENDING'", id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', cancel_reason = 'RELEASE_CANCELLED',"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'IN_PROGRESS')", releaseId);
    }

    /**
     * 隔离回查：仅当仍为 PENDING 时转为 CANCELLED 并写入不可变取消原因，返回影响行数。
     * 调用方已持有任务行锁；影响行数不为 1 视为状态变化失败，应整次回滚。
     */
    public int cancelIfPending(long id, String cancelReason) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', cancel_reason = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'", cancelReason, id);
    }

    /**
     * 设备全部进行中（PENDING 或 IN_PROGRESS）任务，按任务ID升序；隔离回查与解除隔离校验使用。
     */
    public List<RolloutTask> findActiveByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task"
                        + " WHERE device_id = ? AND status IN ('PENDING', 'IN_PROGRESS') ORDER BY id",
                MAPPER, deviceId);
    }

    public long countActiveByDevice(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status IN ('PENDING', 'IN_PROGRESS')",
                Long.class, deviceId);
        return count == null ? 0 : count;
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}
