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
 * 完整性流转只在持有任务行锁（SELECT ... FOR UPDATE）的事务内更新，
 * 并发提交同序号分片由 task_fragment_receipt 唯一约束兜底，最多一笔写入成功。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")), rs.getInt("attempt"),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getString("integrity_reason"), rs.getString("installable_at_utc"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, attempt, first_result,"
            + " integrity_reason, installable_at_utc";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, attempt)"
                            + " VALUES (?, ?, 'PENDING', 1)",
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
     * 查询设备在任意发布单上的最近一条任务（用于发布单撤回后重拉仍可见已撤销任务，且不改写它）。
     */
    public Optional<RolloutTask> findLatestByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE device_id = ?"
                + " ORDER BY id DESC LIMIT 1", MAPPER, deviceId).stream().findFirst();
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * 标记当前尝试完整性失败；调用方已持有任务行锁。
     */
    public void markIntegrityFailed(long id, int attempt, String reason) {
        jdbc.update("UPDATE rollout_task SET status = 'INTEGRITY_FAILED', integrity_reason = ?,"
                + " installable_at_utc = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND attempt = ?",
                reason, id, attempt);
    }

    /**
     * 标记当前尝试可安装；调用方已持有任务行锁。
     */
    public void markInstallable(long id, int attempt, String installableAtUtc) {
        jdbc.update("UPDATE rollout_task SET status = 'INSTALLABLE', integrity_reason = NULL,"
                + " installable_at_utc = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND attempt = ?",
                installableAtUtc, id, attempt);
    }

    /**
     * 完整性失败后重新拉取：仅当任务仍为 INTEGRITY_FAILED 时开启新代次并回到 PENDING。
     * 返回影响行数，0 表示状态已被并发裁决改变，调用方必须重新读取任务。
     */
    public int startNewAttempt(long id, int expectedAttempt) {
        return jdbc.update("UPDATE rollout_task SET attempt = attempt + 1, status = 'PENDING',"
                + " integrity_reason = NULL, installable_at_utc = NULL, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND attempt = ? AND status = 'INTEGRITY_FAILED'", id, expectedAttempt);
    }

    /**
     * 撤销任务：仅未安装状态（PENDING/INSTALLABLE/INTEGRITY_FAILED）可撤销，返回影响行数。
     */
    public int cancelIfUnfinished(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status IN ('PENDING', 'INSTALLABLE', 'INTEGRITY_FAILED')", id);
    }

    /**
     * 撤回发布单：该单所有未安装任务转 CANCELLED；SUCCESS/FAILED 安装终态与 CANCELLED 保持不变。
     */
    public int cancelUnfinishedByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'INSTALLABLE', 'INTEGRITY_FAILED')",
                releaseId);
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
