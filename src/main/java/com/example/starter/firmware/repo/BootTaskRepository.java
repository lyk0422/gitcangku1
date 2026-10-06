package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.BootTask;
import com.example.starter.firmware.domain.BootTaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 双分区试启动任务数据访问。同设备同发布单的尝试序号由 uk_boot_task_attempt 唯一约束保证；
 * 回滚后重试插入新行（新任务ID），不复用旧任务。
 */
@Repository
public class BootTaskRepository {

    private static final RowMapper<BootTask> MAPPER = (rs, rowNum) -> new BootTask(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getInt("attempt_no"), BootTaskStatus.valueOf(rs.getString("status")),
            rs.getString("target_partition"), rs.getString("target_version"),
            rs.getString("trial_started_at_utc"));

    private static final String COLUMNS = "id, release_id, device_id, attempt_no, status,"
            + " target_partition, target_version, trial_started_at_utc";

    private final JdbcTemplate jdbc;

    public BootTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId, int attemptNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO boot_task (release_id, device_id, attempt_no, status)"
                            + " VALUES (?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, attemptNo);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 下一尝试序号。调用方必须已持有发布单行锁，并发拉取串行通过。
     */
    public int nextAttemptNo(long releaseId, String deviceId) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM boot_task"
                        + " WHERE release_id = ? AND device_id = ?",
                Integer.class, releaseId, deviceId);
        return next == null ? 1 : next;
    }

    public Optional<BootTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM boot_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<BootTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM boot_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 同设备同发布单最近一次尝试（任务ID最大者）。
     */
    public Optional<BootTask> findLatestByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM boot_task WHERE release_id = ? AND device_id = ?"
                        + " ORDER BY id DESC LIMIT 1", MAPPER, releaseId, deviceId)
                .stream().findFirst();
    }

    public List<BootTask> findTrialByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM boot_task WHERE release_id = ? AND status = 'TRIAL'"
                + " ORDER BY id", MAPPER, releaseId);
    }

    /**
     * 进入 TRIAL：记录目标分区、目标版本与服务端 UTC 试启动时刻；试启动字段之后不可改。
     */
    public void startTrial(long id, String targetPartition, String targetVersion, String trialStartedAtUtc) {
        jdbc.update("UPDATE boot_task SET status = 'TRIAL', target_partition = ?, target_version = ?,"
                        + " trial_started_at_utc = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                targetPartition, targetVersion, trialStartedAtUtc, id);
    }

    public void commit(long id) {
        jdbc.update("UPDATE boot_task SET status = 'COMMITTED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", id);
    }

    public void markRolledBack(long id) {
        jdbc.update("UPDATE boot_task SET status = 'ROLLED_BACK', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE boot_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
    }

    public List<BootTask> findByRelease(long releaseId, BootTaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM boot_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM boot_task WHERE release_id = ? AND status = ?"
                + " ORDER BY id", MAPPER, releaseId, statusFilter.name());
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boot_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}
