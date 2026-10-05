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
 * 投放任务数据访问。同设备同发布单同尝试序号由唯一约束 uk_task_release_device 保证最多一条；
 * 双分区任务回滚后重试以递增的 attempt_no 插入，产生新任务ID。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getInt("attempt_no"), rs.getString("target_partition"),
                rs.getString("target_version"), rs.getString("trial_started_at_utc"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result, attempt_no,"
            + " target_partition, target_version, trial_started_at_utc";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId, int attemptNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, attempt_no)"
                            + " VALUES (?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, attemptNo);
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

    /**
     * 同设备同发布单的最新一次尝试（attempt_no 最大）。
     */
    public Optional<RolloutTask> findLatestByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task"
                        + " WHERE release_id = ? AND device_id = ? ORDER BY attempt_no DESC LIMIT 1",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    public List<RolloutTask> findTrialsByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task"
                + " WHERE release_id = ? AND status = 'TRIAL' ORDER BY id", MAPPER, releaseId);
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * 双分区试启动登记：仅当仍为 PENDING 时进入 TRIAL 并记录试启动字段，返回影响行数。
     */
    public int startTrial(long id, String targetPartition, String targetVersion, String startedAtUtc) {
        return jdbc.update("UPDATE rollout_task SET status = 'TRIAL', target_partition = ?,"
                        + " target_version = ?, trial_started_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'PENDING'",
                targetPartition, targetVersion, startedAtUtc, id);
    }

    /**
     * 健康确认通过：仅当仍为 TRIAL 时转为 COMMITTED，返回影响行数。
     */
    public int commitTrial(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'COMMITTED', first_result = 'SUCCESS',"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'TRIAL'", id);
    }

    /**
     * 回滚：仅当仍处于 PENDING 或 TRIAL 时转为 ROLLED_BACK，返回影响行数；设备分区与版本不变。
     */
    public int rollback(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'ROLLED_BACK', first_result = 'FAILED',"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('PENDING', 'TRIAL')", id);
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