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
 * 状态迁移均由 WHERE 当前状态 守卫，并发下至多一个事务生效；取消代次冻结保留。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        Integer cancelGeneration = rs.getObject("cancel_generation", Integer.class);
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                cancelGeneration, rs.getString("cancel_operator"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " cancel_generation, cancel_operator";

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

    /**
     * 显式开始：仅 PENDING 可转 INSTALLING，返回影响行数；调用方须先占设备独占安装槽。
     */
    public int start(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'INSTALLING', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PENDING'", id);
    }

    /**
     * 终结任务（SUCCESS/FAILED），仅 INSTALLING 可迁移，返回影响行数。
     */
    public int complete(long id, ReceiptResult result) {
        return jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'INSTALLING'", result.name(), result.name(), id);
    }

    /**
     * 任务取消请求：仅 INSTALLING 可转 CANCEL_REQUESTED，取消代次加一并记录操作者，返回影响行数。
     */
    public int requestCancel(long id, String operator) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCEL_REQUESTED',"
                + " cancel_generation = COALESCE(cancel_generation, 0) + 1, cancel_operator = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'INSTALLING'", operator, id);
    }

    /**
     * 发布整体取消：所有 INSTALLING 任务转 CANCEL_REQUESTED 并生成取消代次，仍占槽，不伪造已停。
     */
    public int requestCancelByRelease(long releaseId, String operator) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCEL_REQUESTED',"
                + " cancel_generation = COALESCE(cancel_generation, 0) + 1, cancel_operator = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE release_id = ? AND status = 'INSTALLING'",
                operator, releaseId);
    }

    /**
     * 停止确认：仅 CANCEL_REQUESTED 且代次一致时转 CANCELLED，返回影响行数。
     */
    public int confirmStop(long id, int generation) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'CANCEL_REQUESTED' AND cancel_generation = ?", id, generation);
    }

    /**
     * 未开始任务直接取消：仅 PENDING 可迁移，返回影响行数；PENDING 不占槽，无需释放。
     */
    public int cancelPendingTask(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PENDING'", id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
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
