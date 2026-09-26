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
 * 分片接收代次（attempt）只在 INTEGRITY_FAILED 后重新拉取时加一，旧代次证据表记录不改写。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getInt("attempt"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result, attempt";

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
     * 取消未终结任务：PENDING、INSTALLABLE、INTEGRITY_FAILED 均转为 CANCELLED。
     */
    public int cancelUnfinishedByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'INSTALLABLE', 'INTEGRITY_FAILED')", releaseId);
    }

    /**
     * 分片核验状态迁移：仅当任务仍处于 PENDING 时生效，返回影响行数。
     * 调用方持有任务行锁，并发下至多一笔生效。
     */
    public int transitionFromPending(long id, TaskStatus target) {
        return jdbc.update("UPDATE rollout_task SET status = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PENDING'", target.name(), id);
    }

    /**
     * 重新拉取开启新尝试代次：仅当任务仍为 INTEGRITY_FAILED 且代次匹配时，
     * 代次加一并回到 PENDING，返回影响行数；旧代次分片证据与核验记录保留不改写。
     */
    public int startNewAttempt(long id, int expectedAttempt) {
        return jdbc.update("UPDATE rollout_task SET status = 'PENDING', attempt = attempt + 1,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'INTEGRITY_FAILED' AND attempt = ?", id, expectedAttempt);
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ?",
                Long.class, releaseId);
        return count == null ? 0 : count;
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}
