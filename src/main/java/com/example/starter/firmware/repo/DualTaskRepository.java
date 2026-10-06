package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DualTask;
import com.example.starter.firmware.domain.DualTaskStatus;
import com.example.starter.firmware.domain.RollbackReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 双分区试启动任务数据访问。同发布单同设备至多一条未终结任务由唯一索引
 * uk_dual_task_active(release_id, active_device) 保证；终结时 active_device 置 NULL，
 * 回滚后重试插入新行从而产生新任务ID。
 */
@Repository
public class DualTaskRepository {

    private static final RowMapper<DualTask> MAPPER = (rs, rowNum) -> {
        String reason = rs.getString("rollback_reason");
        return new DualTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                DualTaskStatus.valueOf(rs.getString("status")),
                rs.getString("target_partition"), rs.getString("target_version"),
                rs.getString("trial_started_at_utc"), rs.getString("settled_at_utc"),
                reason == null ? null : RollbackReason.valueOf(reason));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, target_partition,"
            + " target_version, trial_started_at_utc, settled_at_utc, rollback_reason";

    private final JdbcTemplate jdbc;

    public DualTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dual_task (release_id, device_id, status, active_device)"
                            + " VALUES (?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setString(3, deviceId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DualTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DualTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查找设备在指定发布单下的未终结任务（PENDING/TRIAL），至多一条。
     */
    public Optional<DualTask> findActiveByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_task"
                        + " WHERE release_id = ? AND active_device = ?", MAPPER, releaseId, deviceId)
                .stream().findFirst();
    }

    public List<DualTask> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_task WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }

    /**
     * 发布单下仍处于 TRIAL 的任务，由调用方按服务端时钟判断是否到期。
     */
    public List<DualTask> findTrialByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_task WHERE release_id = ? AND status = 'TRIAL'"
                + " ORDER BY id", MAPPER, releaseId);
    }

    /**
     * PENDING -> TRIAL：冻结目标分区、目标版本与服务端试启动时刻；仅 PENDING 可迁移。
     */
    public int startTrial(long id, String targetPartition, String targetVersion, String trialStartedAtUtc) {
        return jdbc.update("UPDATE dual_task SET status = 'TRIAL', target_partition = ?, target_version = ?,"
                        + " trial_started_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'PENDING'",
                targetPartition, targetVersion, trialStartedAtUtc, id);
    }

    /**
     * TRIAL -> COMMITTED：仅 TRIAL 可迁移，返回影响行数；并发下至多一个事务生效。
     */
    public int commitIfTrial(long id, String settledAtUtc) {
        return jdbc.update("UPDATE dual_task SET status = 'COMMITTED', settled_at_utc = ?,"
                + " active_device = NULL, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'TRIAL'", settledAtUtc, id);
    }

    /**
     * PENDING/TRIAL -> ROLLED_BACK：仅未终结可迁移，返回影响行数；并发下至多一个事务生效。
     */
    public int rollbackIfUnsettled(long id, RollbackReason reason, String settledAtUtc) {
        return jdbc.update("UPDATE dual_task SET status = 'ROLLED_BACK', rollback_reason = ?,"
                        + " settled_at_utc = ?, active_device = NULL, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status IN ('PENDING', 'TRIAL')",
                reason.name(), settledAtUtc, id);
    }

    public long countActiveByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dual_task WHERE release_id = ? AND active_device = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}
