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
 * 完整性字段（代次、要求/已收分片数、摘要快照）随拉取与判定在持有任务行锁的事务内更新。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getInt("attempt_no"), rs.getString("failure_reason"),
                rs.getInt("required_shards"), rs.getInt("received_shards"),
                rs.getString("package_digest"), rs.getString("attempt_finalized_at_utc"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result, attempt_no,"
            + " failure_reason, required_shards, received_shards, package_digest, attempt_finalized_at_utc";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 建任务。遗留发布单 requiredShards=0、packageDigest 为 null；分片发布单固化首代次快照。
     */
    public long insert(long releaseId, String deviceId, int requiredShards, String packageDigest,
                       String createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, attempt_no, required_shards,"
                            + " received_shards, package_digest)"
                            + " VALUES (?, ?, 'PENDING', 1, ?, 0, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, requiredShards);
            ps.setString(4, packageDigest);
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
     * 开新尝试代次：仅 INTEGRITY_FAILED 任务可重拉，代次加一、清空上一代失败信息与进度。
     * 返回影响行数：0 表示状态已被并发改写，调用方按当前状态裁决。
     */
    public int openNewAttempt(long id, int requiredShards, String packageDigest) {
        return jdbc.update("UPDATE rollout_task SET status = 'PENDING', first_result = NULL,"
                + " failure_reason = NULL, attempt_no = attempt_no + 1, required_shards = ?,"
                + " received_shards = 0, package_digest = ?, attempt_finalized_at_utc = NULL,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'INTEGRITY_FAILED'", requiredShards, packageDigest, id);
    }

    /**
     * 记录一笔分片接收后的进度（已收不同序号数）。调用方持有任务行锁。
     */
    public void updateReceivedShards(long id, int receivedShards) {
        jdbc.update("UPDATE rollout_task SET received_shards = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                receivedShards, id);
    }

    /**
     * 判定为可安装。要求/已收数量相等，调用方已在事务内完成全集合校验。
     */
    public void markInstallable(long id, String finalizedAtUtc) {
        jdbc.update("UPDATE rollout_task SET status = 'INSTALLABLE', failure_reason = NULL,"
                + " attempt_finalized_at_utc = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                finalizedAtUtc, id);
    }

    /**
     * 判定为完整性失败，原因码可区分。
     */
    public void markIntegrityFailed(long id, String reason, String finalizedAtUtc) {
        jdbc.update("UPDATE rollout_task SET status = 'INTEGRITY_FAILED', first_result = NULL,"
                + " failure_reason = ?, attempt_finalized_at_utc = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                reason, finalizedAtUtc, id);
    }

    /**
     * 任务撤销：仅未安装（接收中/完整性失败/已判定可安装）可撤销；已安装 SUCCESS/FAILED 不允许。
     */
    public int cancelReceiving(long id) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status IN ('PENDING', 'INTEGRITY_FAILED', 'INSTALLABLE')", id);
    }

    /**
     * 发布单取消：尚未安装的任务（接收中/完整性失败/已判定可安装）一律转 CANCELLED。
     */
    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'INTEGRITY_FAILED', 'INSTALLABLE')",
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
