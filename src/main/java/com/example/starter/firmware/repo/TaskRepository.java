package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.domain.TaskType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条；
 * 设备独占安装槽由唯一索引 uk_task_device_slot 保证同设备至多一个 INSTALLING/CANCEL_REQUESTED 任务。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        int cancelRound = rs.getInt("cancel_round");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                TaskType.valueOf(rs.getString("task_type")),
                rs.getInt("cancel_generation"),
                rs.wasNull() ? null : cancelRound,
                rs.getString("device_slot"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result, task_type,"
            + " cancel_generation, cancel_round, device_slot";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId) {
        return insert(releaseId, deviceId, TaskType.PLAIN);
    }

    /**
     * 创建指定类型任务，初始状态 PENDING（INSTALL 任务此时不占槽，显式开始才占槽）。
     */
    public long insert(long releaseId, String deviceId, TaskType taskType) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, task_type) VALUES (?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setString(3, taskType.name());
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
     * 查找设备独占安装槽当前占用者（device_slot 非空即占用中，唯一索引保证至多一条）。
     */
    public Optional<RolloutTask> findSlotHolder(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE device_slot = ?",
                MAPPER, deviceId).stream().findFirst();
    }

    /**
     * 终结任务（SUCCESS/FAILED）并释放安装槽；首次回执结果一并冻结。
     */
    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, device_slot = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * INSTALL 任务显式开始：PENDING 转 INSTALLING 并占设备独占安装槽，返回影响行数。
     * 槽冲突由 uk_task_device_slot 唯一索引兜底。
     */
    public int startInstall(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'INSTALLING', device_slot = device_id,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PENDING' AND task_type = 'INSTALL'", id);
    }

    /**
     * 取消请求：INSTALLING 转 CANCEL_REQUESTED，取消代次加一并冻结当前监控轮次，保留安装槽。
     */
    public int requestCancel(long id, int cancelRound) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCEL_REQUESTED',"
                + " cancel_generation = cancel_generation + 1, cancel_round = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'INSTALLING'",
                cancelRound, id);
    }

    /**
     * 已为 CANCEL_REQUESTED 的任务再次取消请求：仅递增取消代次并刷新冻结轮次，旧代次停止确认随之失效。
     */
    public int renewCancel(long id, int cancelRound) {
        return jdbc.update("UPDATE rollout_task SET cancel_generation = cancel_generation + 1,"
                + " cancel_round = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'CANCEL_REQUESTED'", cancelRound, id);
    }

    /**
     * 停止确认：仅同代次的 CANCEL_REQUESTED 任务转 CANCELLED 并释放安装槽，返回影响行数。
     */
    public int confirmStop(long id, int cancelGeneration) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', device_slot = NULL,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'CANCEL_REQUESTED' AND cancel_generation = ?",
                id, cancelGeneration);
    }

    /**
     * 未开始任务直接取消（PLAIN 或 INSTALL 的 PENDING 任务均未占槽）。
     */
    public int cancelPending(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PENDING'", id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
    }

    /**
     * 发布整体取消：已开始（INSTALLING）任务转 CANCEL_REQUESTED 待确认，代次加一、保留安装槽，不伪造已停。
     */
    public int requestCancelInstallingByRelease(long releaseId, int cancelRound) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCEL_REQUESTED',"
                + " cancel_generation = cancel_generation + 1, cancel_round = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE release_id = ? AND status = 'INSTALLING'",
                cancelRound, releaseId);
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
